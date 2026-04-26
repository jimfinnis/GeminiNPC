package org.pale.gemininpc;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadLocalRandom;

import com.google.gson.*;
import io.marioslab.basis.template.Template;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.ai.Navigator;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.citizensnpcs.api.util.DataKey;

import net.citizensnpcs.trait.ShopTrait;
import net.citizensnpcs.trait.shop.ItemAction;
import net.citizensnpcs.trait.shop.NPCShopAction;
import org.bukkit.*;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;


import org.checkerframework.checker.units.qual.C;
import org.mcmonkey.sentinel.SentinelTrait;

import org.pale.gemininpc.ai.Chat;
import org.pale.gemininpc.ai.ContextBuilder;
import org.pale.gemininpc.ai.Persona;
import org.pale.gemininpc.commands.CallInfo;
import org.pale.gemininpc.utils.TemplateFunctions;
import org.pale.gemininpc.utils.TransientNotification;
import org.pale.gemininpc.utils.TransientNotificationMap;
import org.pale.gemininpc.waypoints.Waypoint;
import org.pale.gemininpc.waypoints.Waypoints;
import org.pale.jcfutils.region.Region;
import org.pale.jcfutils.region.RegionManager;

import javax.naming.Context;


//This is your trait that will be applied to a npc using the /trait mytraitname command.
//Each NPC gets its own instance of this class.
//the Trait class has a reference to the attached NPC class through the protected field 'npc' or getNPC().
//The Trait class also implements Listener so you can add EventHandlers directly to your trait.
@TraitName("gemininpc") // convenience annotation in recent CitizensAPI versions for specifying trait name
public class GeminiNPCTrait extends Trait {
    /**
     * Initialise the trait.
     */
    public GeminiNPCTrait() {
        super("gemininpc");
        plugin = JavaPlugin.getPlugin(Plugin.class);
    }


    public void log_debug(String s){
        if(debug)Plugin.log(npc.getName()+ ": "+s);
    }

    public boolean isShop() {
        return npc.hasTrait(ShopTrait.class);
    }
    public boolean isSentinel() {
        return npc.hasTrait(SentinelTrait.class);
    }

    // do we have a particular material (minecraft name) in our inventory?
    public Object has(String name) {
        if (npc.getEntity() instanceof Player npcp) {
            name = name.trim();
            plugin.getLogger().info("looking for "+name+" in inventory of "+npc.getName());
            Inventory inv = npcp.getInventory();
            Material mat = Material.getMaterial(name.toUpperCase());
            if(mat==null){
                plugin.getLogger().info("Material not found; iterating over items");
                // it's not a material but might be a display name, so check everything.
                for (ItemStack item : inv.getContents()) {
                    if (item == null) continue; // empty slot
                    ItemMeta meta = item.getItemMeta();
                    if(meta != null){
                        plugin.getLogger().info("   metadata displayname="+meta.getDisplayName());
                        plugin.getLogger().info("   metadata itemname   = "+meta.getItemName());
                        if (meta.hasDisplayName() && meta.getDisplayName().equalsIgnoreCase(name)) {
                            plugin.getLogger().info("Got it (DNAME)!");
                            return true;
                        }
                        if (meta.hasItemName() && meta.getItemName().equalsIgnoreCase(name)){
                            plugin.getLogger().info("Got it (CNAME)!");
                            return true;
                        }
                    }
                }
                return false;
            }
            return inv.contains(mat);
        }
        return false;
    }

    enum NavCompletionCode {
        ARRIVED("arrived"),
        CANCELLED("cancelled");

        public final String label;
        NavCompletionCode(String label){
            this.label = label;
        }
    }

    // this holds an object that creates the context - the JSON object containing
    // state data about the NPC and its environment.
    private ContextBuilder contextBuilder = null;


    public final Plugin plugin;           // useful pointer back to the plugin shared by all traits
    private int tickint = 0;        // a counter to slow down updates
    public long timeSpawned = 0;    // ticks since spawn
    Location navTarget;     // current path destination using our waypoints (not Chatcitizen's) or null
    boolean debug;
    public String seedString = null;

    double npcRespondProb = 0; // probability that we will respond to something an NPC says (as opposed to a player)

    // So, tell me who hurt you? And when?

    public Entity whoDamagedBy;
    public long whenLastDamaged = -1; // -1 is never damaged or too long ago to care about


    // these could be the same. If the visible monster is null, there's no visible monster - but one can be "heard"
    // This object will "hang on" to the monster for a while, so we can ask the AI about it.
    // The keys we will use are "visible" and "heard"

    public record MonsterData(String m, double dist) {}
    public final TransientNotification<MonsterData> nearestMonster = new TransientNotification<>(30);
    public final TransientNotification<MonsterData> nearestVisibleMonster = new TransientNotification<>(20);

    // range of entity scanner
    static final double NEARBY_ENTITIES_SCAN_DIST = 10;
    static final double NEARBY_ENTITIES_SCAN_DISTY = 4;

    // distances for greeting players
    static final double GREET_DIST = 8;
    static final double GREET_DISTY = 2;

    static final int MAXTICKINT = 10;   // how many ticks it takes before an update

    // players which we have recently seen hang around in this for a while. The message type is "object"
    // because we don't care what it is - it's not used.
    final TransientNotificationMap<Object> recentlySeenPlayers = new TransientNotificationMap<>(60);

    // throttles the infrequent update on individual NPCs
    final TransientNotification<Object> updateInfrequentRecently = new TransientNotification<>(60);

    // The "chat" part of the GenAI api is synchronous, so we use a queue and a thread to
    // make it non-blocking. Requests are sent to the AI in a thread, and when the response
    // is returned it is added to this queue. We don't need to store the player, because the
    // NPC will just "say" the response to all players in range. The queue is read inside the
    // update method by polling - not ideal, but it's very quick and the update infrequent.
    final ConcurrentLinkedQueue<Chat.Response> queue = new ConcurrentLinkedQueue<>();

    // this is the Chat API object - it's created the first time you call the respondTo method,
    // or when it's called after you change the persona (which sets this to zero)
    Chat chat = null; // will be created the first time you chat

    // this is the system instruction that will be sent. It's the "default" personality. Yes, it
    // can be creepy as heck.
    static final String DEFAULT_PERSONA = "You have no memory of who or what you are.";

    public String personaName = "default"; // the name of the persona
    public String gender = null;

    public final Waypoints waypoints = new Waypoints();

    // the template functions object, created when we reset or init. Holds some state.

    private TemplateFunctions templateFunctions = null;

    /**
     * This gets called very infrequently, randomly. And never more than the per-NPC
     * updateInfrequentRecently allows.
     */
    public void updateInfrequent() {
        if(updateInfrequentRecently.active()) {
            return;
        }
        updateInfrequentRecently.set(null);
        respondTo(null, "(you look around)");
    }

    // we can set one of these up to be called when navigation completes (or fails)
    interface NavCompletionFunction {
        void call(NavCompletionCode code, double dist);
    }
    NavCompletionFunction navCompletionHandler;

    void navComplete(NavCompletionCode navCompletionCode) {
        if(navTarget != null) {
            // if navTarget is null, this is an navigation target set via a pathTo call. We will
            // call a completion handler if one is available. We also teleport if we didn't get
            // there!
            if(npc.getStoredLocation().getWorld() == navTarget.getWorld()) {
                // we are in the same world, so we can check the distance - we shouldn't get the
                // case where nav to a place in a different world completes!
                double dist = npc.getStoredLocation().distance(navTarget);
                if (debug) log_debug("Navigator completed with code: " + navCompletionCode.label + ", dist: " + dist);
                if (navCompletionHandler != null) {
                    navCompletionHandler.call(navCompletionCode, dist);
                    navCompletionHandler = null;
                }
                if (dist > 2.0) {
                    // emergency teleport. If we didn't get there, or the system claims we got there but we're still
                    // a fair distance away, TP to it. Hate this.
                    Plugin.log("Navigator did not arrive at destination, teleporting to " + navTarget);
                    npc.teleport(navTarget.add(0, 1, 0), PlayerTeleportEvent.TeleportCause.PLUGIN);
                }
            }
            navTarget = null;
        }
    }

    public void setGender(String s){
        gender = s;
    }




    // Here you should load up any values you have previously saved (optional).
    // This does NOT get called when applying the trait for the first time, only loading onto an existing npc at server start.
    // This is called AFTER onAttach so you can load defaults in onAttach, and they will be overridden here.
    // This is called BEFORE onSpawn, npc.getBukkitEntity() will return null.
    public void load(DataKey key) {
        // we load the entire persona string.
        personaName = key.getString("pname", "default");
        // if gender is null when we come to actually generate the persona string,
        // (i.e. a value is not provided in the NPC) we will use the persona to set it, which in turn
        // may get it from the plugin's config.
        gender = key.getString("gender", null);
        // load the NPC respond probability
        npcRespondProb = key.getDouble("npc_respond_prob", Plugin.getInstance().defaultNPCRespondProb);
        // and the seed if it has been set - otherwise we'll use the name which was set in onAttach
        seedString = key.getString("seed_string", seedString);

        waypoints.load(key);
    }

    // Save settings for this NPC (optional). These values will be persisted to the Citizens saves file
    public void save(DataKey key) {
        key.setString("pname", personaName);
        key.setString("gender",gender);
        key.setString("seed_string", seedString);
        key.setDouble("npc_respond_prob", npcRespondProb);

        waypoints.save(key);
    }

    // Called every tick
    @Override
    public void run() {
        if (tickint++ == MAXTICKINT) { // to reduce CPU usage - this is about 0.5Hz.
            update();
            tickint = 0;
        }
        timeSpawned++;
    }

    /**
     * Run code when your trait is attached to a NPC.
     * This is called BEFORE onSpawn, so npc.getBukkitEntity() will return null
     * This would be a good place to load configurable defaults for new NPCs.
     */
    @Override
    public void onAttach() {
        plugin.getServer().getLogger().info(npc.getName() + " has been assigned GeminiNPC!");
        seedString = npc.getName();
    }

    /**
     * Run code when the NPC is despawned. This is called before the entity actually despawns so
     * npc.getBukkitEntity() is still valid.
     */
    @Override
    public void onDespawn() {
        // Plugin.log(" Despawn run on " + npc.getFullName());

        // remove this NPC from the plugin's set of NPCs which have the trait
        plugin.removeChatter(npc);
    }

    /**
     * Run code when the NPC is spawned. Note that npc.getBukkitEntity() will be null until this method is called.
     * This is called AFTER onAttach and AFTER Load when the server is started.
     */
    @Override
    public void onSpawn() {
        // Plugin.log(" Spawn run on " + npc.getFullName());
        // Add the NPC to the plugin's set of NPCs which have the trait
        plugin.addChatter(npc);
        tickint = ThreadLocalRandom.current().nextInt(0, MAXTICKINT); // randomise the tickint to avoid all NPCs updating at once
    }

    /**
     * This is called when the trait is removed from the NPC. This is called before onDespawn, so the entity is still valid.
     */
    @Override
    public void onRemove() {
        // just to be damn sure
        plugin.removeChatter(npc);
    }

    /**
     * Called by the plugin when we made a kill
     */
    void onKill(String mobname){
        respondTo(null, String.format("(%s killed a %s)", npc.getFullName(), mobname));
    }

    /**
     * Called when we get damage from entity
     */
    void onDamagedEntity(Entity defender){
        whoDamagedBy = defender;
        whenLastDamaged = System.currentTimeMillis();
    }

    /**
     * This is called when a player right-clicks on an NPC. The held item is transferred into the NPCs
     * inventory, and the respondTo function is called with a special message.
     * Note that this WILL NOT be called if the NPC has a shop.
     */
    void give(Player p) {
        ItemStack playerStack = p.getInventory().getItemInMainHand();
        Material mat = playerStack.getType();
        if (mat == Material.AIR) return;     // nothing to take

        // we can only give to player-type npcs. For others, the item will just disappear.
        if (npc.getEntity() instanceof Player npcp) {
            Inventory inv = npcp.getInventory();
            p.getInventory().setItemInMainHand(null);   // remove from main hand
            // add to NPC inventory getting leftovers
            HashMap<Integer, ItemStack> leftover = inv.addItem(playerStack);
            if (!leftover.isEmpty()) {  // handle leftovers.
                // we couldn't add the item to the NPC. Send a message and give up.
                respondTo(p, "(tries to give you " + playerStack.getType().name() + " but you have no room)");
                p.getWorld().dropItemNaturally(p.getLocation(),leftover.get(0));
                return;
            }
        }

        // and send the message to the AI
        respondTo(p, "(gives you " + playerStack.getType().name() + ")");
    }

    // this timer controls responses to purchases. When a purchase happens, it is set to a few seconds in the
    // future. This is set each time the purchase happens. When it expires, that means a purchase hasn't
    // happened for a little while and we can now respond to all the purchases.

    long purchaseTimer = 0;
    Map<Player, Map<String, Integer>> itemsBoughtByPlayer = new HashMap<>(); // player -> item type -> amount

    private void checkPurchaseTimer(){
        if(purchaseTimer>0 && System.currentTimeMillis() > purchaseTimer){
            // we have a purchase timer that has expired, so we can now send the purchases to the AI.
            for(Map.Entry<Player, Map<String, Integer>> entry : itemsBoughtByPlayer.entrySet()){
                Player p = entry.getKey();
                Map<String, Integer> itemsBought = entry.getValue();
                List<String> items = new ArrayList<>();
                for(Map.Entry<String, Integer> itemEntry : itemsBought.entrySet()){
                    String itemType = itemEntry.getKey();
                    int amount = itemEntry.getValue();
                    items.add(amount + "x " + itemType);
                }
                String itemsString = String.join(", ", items);
                // send the response to the AI
                respondTo(p, "(" + p.getDisplayName() + " bought " + itemsString + " from you)");
            }
            itemsBoughtByPlayer.clear(); // clear the map
            purchaseTimer = 0; // stop the timer
        }
    }

    /**
     * Respond to a player buying items from an NPC with a shop. We just add the items
     * to the itemsBought map; later we will send a message to the AI once a timer has
     * expired so that we don't send a lot of individual requests.
     *
     */
    void onShopPurchase(Player p, List<ItemStack> itemList){
        // get or create the map for this player
        Map<String, Integer> itemsBought = itemsBoughtByPlayer.computeIfAbsent(p, k -> new HashMap<>());
        // for each stack, add the item type and amount to that map.
        for(ItemStack s: itemList){
            if(itemsBought.containsKey(s.getType().name())){
                itemsBought.put(s.getType().name(), itemsBought.get(s.getType().name()) + s.getAmount());
            } else {
                itemsBought.put(s.getType().name(), s.getAmount());
            }
        }
        // reset the timer to n milliseconds in the future
        purchaseTimer = System.currentTimeMillis() + Plugin.getInstance().purchaseTimeout;
    }

    /**
     * Handle a JSON response object, which contains response, command and player elements.
     */
    private void processResponse(Chat.Response r){
        String response = r.text;
        String playerName = r.player;
        String action = r.action;
        if (response != null) {
            for (NearbyPlayer p : nearbyPlayers) {
                // Plugin.log("message in queue : " + s);
                String outmsg;
                if(playerName==null || playerName.isEmpty())
                    outmsg = ChatColor.AQUA + "[" + npc.getFullName() + "] " + ChatColor.WHITE + response;
                else
                    outmsg = ChatColor.AQUA + "[" + npc.getFullName() + " -> " + playerName + "] " + ChatColor.WHITE + response;
                p.p.sendMessage(outmsg);
            }
        } else {
            Plugin.log("null msg");
        }
        if (action != null && !action.isBlank()) {
//            performAction(playerName==null?null:plugin.getServer().getPlayer(playerName), action);
            plugin.actionRegistry.handleAction(this,
                    playerName==null?null:plugin.getServer().getPlayer(playerName),
                    action);
            Plugin.log("ACTION HANDLING: "+action);
        }
    }

    /**
     * Scan nearby entities for both players and mobs. Once a second should do it.
     * One result is the nearbyPlayers set, which will only have members IF one of the nearby
     * players is a real player and not an NPC to avoid wasting AI requests (if a tree falls in
     * the forest and there's no-one to hear it, does it make a sound? Here, it doesn't).
     * Another result is the nearestMonster (could be null) and the nearestMonsterDistance
     *
     * @param d range in x and y
     * @param dy range in y
     *
     */
    @SuppressWarnings("SameParameterValue")
    private void updateNearbyEntities(double d, double dy){
        Set<GeminiNPCTrait.NearbyPlayer> r = new HashSet<>();
        boolean nonNPCPresent = false;
        Location myLocation = npc.getStoredLocation();

        // if we can't get the entity, we can't do anything. Perhaps because it just despawned?? That's
        // when I'm getting this error - just after NPC death events.
        if(npc.getEntity()==null)return;

        // also, it needs to be a LivingEntity so we can run hasLineOfSight on it.
        if(!(npc.getEntity() instanceof LivingEntity))
            return;

        LivingEntity npcEntity = (LivingEntity) npc.getEntity();

        for (Entity e : npc.getEntity().getNearbyEntities(d, dy, d)) {
            if (e instanceof Player p) {
                if (!CitizensAPI.getNPCRegistry().isNPC(e))
                    nonNPCPresent = true;
                if (npcEntity.hasLineOfSight(e)) {
                    double dx = myLocation.getX() - p.getLocation().getX();
                    double dz = myLocation.getZ() - p.getLocation().getZ();
                    double dist = Math.sqrt(dx * dx + dz * dz);
                    double disty = myLocation.getY() - p.getLocation().getY();
                    r.add(new GeminiNPCTrait.NearbyPlayer(p, dist, disty));
                    if(debug)
                        log_debug(String.format("%s scan ADDING %s (dist %.2f dy %.2f)",
                                npc.getEntity().getName(), p.getDisplayName(), dist, disty));
                }
            } else if(e instanceof Monster m){
                String mname = m.getName();
                // should be same world because getNearbyEntities only returns entities in the same world
                double dist = m.getLocation().distance(npc.getEntity().getLocation());
                GeminiNPCTrait.MonsterData nm = nearestMonster.get();
                if (nm == null || dist < nm.dist) {
                    nearestMonster.set(new GeminiNPCTrait.MonsterData(mname, dist));
                    if(debug)log_debug(String.format("%s detected monster %s (dist %.2f)",
                            npc.getEntity().getName(), mname, dist));
                    if(npcEntity.hasLineOfSight(m)){
                        GeminiNPCTrait.MonsterData nvm = nearestVisibleMonster.get();
                        if (nvm == null || dist < nvm.dist) {
                            nearestVisibleMonster.set(new GeminiNPCTrait.MonsterData(mname, dist));
                            if(debug)log_debug(String.format("%s detected visible monster %s (dist %.2f)",
                                    npc.getEntity().getName(), m.getName(), dist));
                        }
                    }
                }
            }
        }
        // if there are no *real* players nearby, don't waste AI tokens on greeting.
        if (nonNPCPresent)
            nearbyPlayers = r;
        else
            nearbyPlayers = emptySet;
        if(debug)log_debug("Nearby: "+String.join(",",nearbyPlayers.stream().map(p->p.p.getName()).toList()));
    }


    /**
     * This is called every tick, and is where we do the work. We check the queue for messages,
     * and if there are any, we send them to the players in range.
     */
    void update() {
        checkPurchaseTimer();   // check if we have a purchase timer that has expired, and if so, send the purchases to the AI.
        // check the queue - if there are any messages, speak them.
        while (!queue.isEmpty()) {
            Chat.Response r = queue.poll();
            // parse the response
            Plugin.log(npc.getName() +" returned JSON string is: " + r.toString());
            processResponse(r);
        }

        updateNearbyEntities(NEARBY_ENTITIES_SCAN_DIST, NEARBY_ENTITIES_SCAN_DISTY);
        processGreet();

        if(debug){
            Navigator nav = npc.getNavigator();
            if(nav.isNavigating()){
                log_debug("Navigator navigating:"+nav.isNavigating()+", strategydest:"+nav.getPathStrategy().getCurrentDestination());
            }
        }
    }

    /**
     * Get the system instructions string from the plugin, via the appropriate Persona, applying templates as necessary.
     * Only done when a chat is created!
     * @return the system instructions from the persona
     */
    public String getSystemInstructions() {
        Persona persona = Plugin.getInstance().personae.get(personaName);
        String s;
        if (persona == null) {
            // if we don't have a persona, use the default.
            s = DEFAULT_PERSONA;
            if(gender==null)    // no gender given yet
                gender = plugin.defaultGender;  // there's no persona to get the default; use the plugin
        } else {
            if(gender==null)    // no gender given yet
                gender = persona.defaultGender; // we can get the gender from the persona
            s = persona.generateSystemInstructions(this);
        }
        return s;
    }

    void setPersona(String pname) {
        personaName = pname;
        chat = null; // a new chat will need to be made.
        templateFunctions = null; // and so will a new template functions object
    }

    public record NearbyPlayer(Player p,   // player
                        double d,   // distance in x and z
                        double dy   // distance in y
    ){}
    final Set<NearbyPlayer> emptySet  = new HashSet<>(); // avoids reinstantiations
    /**
     * Set of nearby visible players and distances - empty if no nearby player is a real player.
     */
    Set<NearbyPlayer> nearbyPlayers = emptySet;

    /**
     * Get the nearby players
     */
    public Set<NearbyPlayer> getNearbyPlayers(){
        return Collections.unmodifiableSet(nearbyPlayers);
    }


    /**
     * If the chat - the link to the upstream LLM - is null, create a new one setting
     * up the config and the system instructions. This is done when we respond to
     * a chat event for the first time.
     */
    private void createChatIfNull(){
        if (chat == null) {
            String systemInstruction = getSystemInstructions();
            if(plugin.showSystemInstructions)
                Plugin.log("System instruction for "+npc.getName()+" is "+ systemInstruction);

            // create new chat.
            chat = Chat.builder()
                    .maxMessages(30)
                    .systemInstruction(systemInstruction)
                    .build(plugin.model.model);

            log_debug("NPC " + npc.getFullName() + " has been created with model " + plugin.model);
        }
    }

    static private String getItemStringFromShopActions(List<NPCShopAction> acts){
        var x = acts.stream().map(NPCShopAction::describe).toList();
        return String.join(", ", x);
    }

    private Material getItemMaterialFromShopActions(List<NPCShopAction> acts){
        // work out the material which is the result of an action. We only take ItemActions into
        // account, and we stop at the first one.
        if(acts.isEmpty()) return null;
        for(NPCShopAction act : acts){
            if(act instanceof ItemAction ia){
                // we have an item action, so return the material. We're going to ignore
                // any stack after the first.
                if(!ia.items.isEmpty()){
                    ItemStack item = ia.items.getFirst();
                    if(item!=null)return item.getType();
                }
            }
        }
        return null; // no item action found
    }

    private void getPricesForShopPage(ShopTrait.NPCShopPage page, JsonArray buyList, JsonArray sellList) {
        // adds to 2 JSON arrays - one for buying and one for selling.
        // vile, vile, vile. There's no way of getting the item count.

        for(int i=0;i<45;i++){ // max items in a 5x9 shop. It's annoying, this.
            var item = page.getItem(i);
            if(item==null) continue; // item is null, skip it.
            var result = item.getResult();
            var cost = item.getCost();

            // var resultMat = getItemMaterialFromShopActions(result);
            var costMat = getItemMaterialFromShopActions(cost);
            var displayMat = item.getDisplayItem(null).getType(); // this is the item that is displayed in the shop, not the result or cost.

            JsonObject obj = new JsonObject();
            if(costMat == displayMat){
                // if the item displayed in the shop is the COST item, we are buying this kind of thing.
                obj.addProperty("item", getItemStringFromShopActions(cost));
                obj.addProperty("value", getItemStringFromShopActions(result));
                buyList.add(obj);
            } else {
                // otherwise, we are selling this kind of thing.
                obj.addProperty("item", getItemStringFromShopActions(result));
                obj.addProperty("value", getItemStringFromShopActions(cost));
                sellList.add(obj);
            }
        }
    }

    public JsonObject getShopInstructions() {
        JsonObject obj = new JsonObject();
        obj.addProperty("information", plugin.getText("shop-instruction"));
        // add the shop items
        var shop = npc.getOrAddTrait(ShopTrait.class).getDefaultShop();
        // we can only get the first page because for some reason there's no way of getting
        // the number of pages, and the code uses a getOrAdd.. pattern.

        JsonArray buyList = new JsonArray();
        JsonArray sellList = new JsonArray();

        for(var page: shop.getPages()) {
            getPricesForShopPage(page, buyList, sellList);
        }
        obj.add("items-you-buy", buyList);
        obj.add("items-you-sell", sellList);
        return obj;
    }


    /**
     * This is called when the NPC is spoken to. It will be called from the
     * ChatEventListener when a player sends a message. We check to see if the
     * player is in range, and if so, we send the message to the AI in a thread.
     * The response will be added to a queue which is read in the update. That
     * makes this effectively non-blocking.
     *
     * @param player The player who spoke to the NPC.
     * @param utterance  The message they sent.
     */
    public void respondTo(Player player, String utterance) {

        if(player!=null && player.hasMetadata("NPC")) {
            // here we are responding to an NPC. We only allow this sometimes, according to npcRespondProb
            if(ThreadLocalRandom.current().nextDouble()>npcRespondProb)
                return;
        }

        // if the chat session is null, we need to create it.
        createChatIfNull();

        // limit rate globally - across all chats!
        if(plugin.eventRateTracker.getEventsInLastMinute()>20){
            Plugin.log("Rate limit exceeded, not responding to " + player.getDisplayName());
            return;
        }

        if(!plugin.callsEnabled) {
            plugin.getServer().getLogger().warning("AI model calls are disabled");
            return;
        }

        // look for nearby players, and only do something if there are some.
        // Are any players less than 12m away?
        if (nearbyPlayers.stream().anyMatch(p -> p.d < 12)) {
            // start a new thread which sends to the AI and waits for the result
            new Thread(() -> {
                plugin.eventRateTracker.event();
                String input;
                if(player==null){
                    input = "event: "+utterance;
                } else {
                    input = ChatColor.stripColor(player.getDisplayName()) + ": " + utterance;
                }
                JsonObject output = new JsonObject();

                output.add("context", getContextBuilder().getContextDiffs());
                output.add("input", new JsonPrimitive(input));

                String outString = output.toString();
                plugin.getServer().getLogger().info("Sending to AI: " + outString);
                plugin.request_count++;
                // here we get the response
                try {
                    Chat.Response response = chat.sendAndGetResponse(outString);
                    if (response == null) {
                        plugin.getServer().getLogger().severe("No response");
                        return;
                    }
                    // otherwise we're all good. Queue the message.
                    queue.offer(response);
                    plugin.getServer().getLogger().info("Response received: "+ response);

                } catch(Exception e){
                    if(e.getMessage()==null){
                        plugin.getLogger().severe("That's weird, an exception: "+e);
                    }
                    plugin.getLogger().severe("That's weird, an exception: "+e.getMessage());
                }
            }).start();
        }
    }

    private void processGreet() {
        // pick one who isn't in the "near players for greet" list - i.e. who has just turned up
        for (NearbyPlayer np : nearbyPlayers) {
            Player p = np.p;
            if (np.d < GREET_DIST && np.dy < GREET_DISTY) {
                if(debug) {
                    var xx = recentlySeenPlayers.rawGet(p.getName());
                    log_debug("Greet: " + p.getName() + ", recently seen: " + xx);
                }

                // if we haven't seen this player recently
                if (!recentlySeenPlayers.has(p.getName())) {
                    // greet them by passing a special input to respondTo
                    respondTo(p, "(enters)");
                }
                // we always do this when they're nearby to reset their timer or add them
                recentlySeenPlayers.add(p.getName(), null);
            }
        }
    }

    public void pathTo(String name) throws Waypoints.Exception {
        navTarget = waypoints.pathTo(this, name);
    }

    public boolean isAtWaypointOrInRegion(String name) {
        Location loc = npc.getStoredLocation();
        name = name.trim();
        Waypoints.NearWaypointResult n = waypoints.getNearWaypoint(loc, 100);
        if(n==null) {
            // do a region check instead
            RegionManager rm = RegionManager.getManager(loc.getWorld());
            Set<Region> regs = rm.getRegionSet(loc, false);
            for(Region r: regs){
                if(r.name.equalsIgnoreCase(name))return true;
            }
            return false;

        };
        return (n.waypoint().name.equalsIgnoreCase(name));
    }

    /**
     * Destroy any existing chat session, forcing a reinitialise and complete local
     * memory loss!
     */
    void reset(){
        if(chat!=null){
            // both of these are created on demand
            chat = null;
            contextBuilder = null;
            templateFunctions = null;
        }
    }

    public TemplateFunctions getTemplateFunctions(){
        if(templateFunctions==null){
            templateFunctions = new TemplateFunctions(this);
        }
        return templateFunctions;
    }

    public ContextBuilder getContextBuilder(){
        if(contextBuilder == null){
            contextBuilder = new ContextBuilder(this);
        }
        return contextBuilder;
    }

    /**
     * Show debugging data
     */
    void showInfo(CallInfo c) {
        c.msg("NPC " + getNPC().getName());
        c.msg("  Persona: " + personaName + ", gender: "+gender);
        c.msg("  NPC respond probability: "+npcRespondProb);
        c.msg("  Waypoints:");
        for (String name : waypoints.getWaypointNames()) {
            try {
                Waypoint w = waypoints.getWaypoint(name);
                c.msg("    " + name + " : " + w.toString());
            } catch (Waypoints.Exception e) {
                c.msg("    " + name + " : ERROR: " + e.getMessage());
            }
        }
        c.msg("Recently seen players:");
        for (var entry : recentlySeenPlayers.getMap().entrySet()) {
            c.msg("  " + entry.getKey() + " : " + entry.getValue().timeUntilExpiry());
        }

        if (npc.getNavigator().isNavigating()) {
            c.msg("  Navigator target: " + npc.getNavigator().getTargetAsLocation());
            c.msg("  Path strategy: " + npc.getNavigator().getPathStrategy().getCurrentDestination());
        } else {
            c.msg("  Not navigating");
            if(navTarget != null) {
                c.msg("  Ooops - still have nav target: " + navTarget);
            }
        }
    }
}
