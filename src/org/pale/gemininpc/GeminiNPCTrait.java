package org.pale.gemininpc;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import com.google.gson.*;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.ai.Navigator;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.citizensnpcs.api.util.DataKey;

import net.citizensnpcs.trait.ShopTrait;
import net.citizensnpcs.trait.shop.ItemAction;
import net.citizensnpcs.trait.shop.NPCShopAction;
import org.bukkit.*;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.entity.Monster;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;


import com.google.genai.Chat;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;
import com.google.genai.types.Content;
import org.mcmonkey.sentinel.SentinelTrait;
import org.pale.gemininpc.command.CallInfo;
import org.pale.gemininpc.plugininterfaces.Sentinel;
import org.pale.gemininpc.utils.TransientNotification;
import org.pale.gemininpc.utils.TransientNotificationMap;
import org.pale.gemininpc.waypoints.Waypoint;
import org.pale.gemininpc.waypoints.Waypoints;
import org.pale.jcfutils.region.Region;
import org.pale.jcfutils.region.RegionManager;

import static org.pale.gemininpc.utils.Json.getDifferences;


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

    void log_debug(String s){
        if(debug)Plugin.log(npc.getName()+ ": "+s);
    }

    public boolean isShop() {
        return npc.hasTrait(ShopTrait.class);
    }

    enum NavCompletionCode {
        ARRIVED("arrived"),
        CANCELLED("cancelled");

        public final String label;
        NavCompletionCode(String label){
            this.label = label;
        }
    }

    final Plugin plugin;           // useful pointer back to the plugin shared by all traits
    private int tickint = 0;        // a counter to slow down updates
    public long timeSpawned = 0;    // ticks since spawn
    Location navTarget;     // current path destination using our waypoints (not Chatcitizen's) or null
    boolean debug;

    // So, tell me who hurt you? And when?

    Entity whoDamagedBy;
    long whenLastDamaged = -1; // -1 is never damaged or too long ago to care about

    // these could be the same. If the visible monster is null, there's no visible monster - but one can be "heard"
    // This object will "hang on" to the monster for a while, so we can ask the AI about it.
    // The keys we will use are "visible" and "heard"

    record MonsterData(String m, double dist) {}
    final TransientNotification<MonsterData> nearestMonster = new TransientNotification<>(30);
    final TransientNotification<MonsterData> nearestVisibleMonster = new TransientNotification<>(20);

    // range of entity scanner
    static final double NEARBY_ENTITIES_SCAN_DIST = 10;
    static final double NEARBY_ENTITIES_SCAN_DISTY = 4;

    // distances at which NPCs notice bystander players and NPCs (i.e. data about them
    // is sent to the AI)
    static final double VERY_CLOSE_PLAYERS_DIST = 9;
    static final double VERY_CLOSE_PLAYERS_DISTY = 3;

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
    final ConcurrentLinkedQueue<String> queue = new ConcurrentLinkedQueue<>();

    // this is the Chat API object - it's created the first time you call the respondTo method,
    // or when it's called after you change the persona (which sets this to zero)
    Chat chat = null; // will be created the first time you chat
    // this is the system instruction that will be sent. It's the "default" personality. Yes, it
    // can be creepy as heck.
    static final String DEFAULT_PERSONA = "You have no memory of who or what you are.";

    String personaName = "default"; // the name of the persona
    String gender = null;

    final Waypoints waypoints = new Waypoints();

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
        waypoints.load(key);
    }

    // Save settings for this NPC (optional). These values will be persisted to the Citizens saves file
    public void save(DataKey key) {
        key.setString("pname", personaName);
        key.setString("gender",gender);
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
     *
     * Note that this WILL NOT be called if the NPC has a shop.
     */
    void give(Player p) {
        ItemStack playerStack = p.getInventory().getItemInMainHand();
        Material mat = playerStack.getType();
        if (mat == Material.AIR) return;     // nothing to take

        // we only want to take one item; make a new stack of just that
        ItemStack st = new ItemStack(mat, 1);

        // we can only give to player-type npcs. For others, the item will just disappear.
        if (npc.getEntity() instanceof Player npcp) {
            // first we add to the NPC.
            Inventory inv = npcp.getInventory();
            HashMap<Integer, ItemStack> leftover = inv.addItem(st);
            if (!leftover.isEmpty()) {
                // we couldn't add the item to the NPC. Send a message and give up.
                respondTo(p, "(tries to give you " + st.getType().name() + " but you have no room)");
                return;
            }
        }

        // we added the item to the NPC (or didn't need to), so we can remove it from the player.
        // remove from player
        int newAmount = playerStack.getAmount() - 1;
        if (newAmount <= 0) {
            p.getInventory().setItemInMainHand(null);
        } else {
            playerStack.setAmount(newAmount);
        }

        // and send the message to the AI
        respondTo(p, "(gives you " + st.getType().name() + ")");
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
     * There's a command in the reponse and we should run it.
     *
     * @param p       the player in the reponse (i.e. who we are responding to)
     * @param command the command
     */
    private void performCommand(Player p, String command) {
        boolean hasSentinel = npc.hasTrait(SentinelTrait.class);
        Sentinel s = Plugin.getInstance().sentinelPlugin;
        if (command.startsWith("give ")) {
            String mname = command.substring(5).toUpperCase().trim().replaceAll(" ", "_");
            Material mat = Material.getMaterial(mname);
            if (mat != null) {
                ItemStack st = new ItemStack(mat, 1);
                HashMap<Integer, ItemStack> map = p.getInventory().addItem(st);
                if (map.isEmpty()) {
                    // we added the item to the player
                    p.sendMessage(ChatColor.AQUA + npc.getFullName() + " gives you " + st.getType().name());
                    // if the npc has one, remove it
                    if (npc.getEntity() instanceof Player npcp) {
                        Inventory inv = npcp.getInventory();
                        inv.removeItem(st);
                    }
                } else {
                    p.sendMessage(ChatColor.RED + npc.getFullName() + " tried to give you " + st.getType().name() + " but your inventory is full.");
                }
            } else {
                log_debug("Bad material name in command: " + command);
            }
        }
        else if(command.startsWith("setguard")){
            if(hasSentinel){
                String name = command.substring(9).trim();
                if(name.equalsIgnoreCase("none")){
                    s.setGuard(npc, null);
                    Plugin.log("NPC " + npc.getFullName() + " unguarded.");
                } else {
                    Player p2 = plugin.getServer().getPlayer(name);
                    if(p2 != null) {
                        s.setGuard(npc, p2.getUniqueId());
                        Plugin.log("NPC " + npc.getFullName() + " is now guarding " + p2.getDisplayName());
                    } else {
                        Plugin.log("Cannot find player to guard: " + name);
                    }
                }
            } else {
                Plugin.log("NPC " + npc.getFullName() + " does not have Sentinel.");
            }
        } else if(command.startsWith("unguard")){
            if(hasSentinel){
                s.setGuard(npc, null);
                Plugin.log("NPC " + npc.getFullName() + " unguarded.");
            } else {
                Plugin.log("NPC " + npc.getFullName() + " does not have Sentinel.");
            }
        } else if(command.startsWith("go ")){
            String name = command.substring(3).trim();
            if(name.equalsIgnoreCase("none")){
                npc.getNavigator().cancelNavigation();
                Plugin.log("NPC " + npc.getFullName() + " got a 'go none'.");
            } else {
                try {
                    pathTo(name);
                    Plugin.log("NPC " + npc.getFullName() + " is now going to waypoint "+name);
                } catch(Waypoints.Exception e) {
                    Plugin.log("Cannot find waypoint: " + name);
                }
            }
        }

    }

    /**
     * Handle a JSON response object, which contains response, command and player elements.
     */
    private void processResponse(JsonElement json){
        // get the response, command and player
        JsonObject o = json.getAsJsonObject();
        JsonElement tmp;
        tmp = o.get("response");
        String response = (tmp == null || tmp.isJsonNull()) ? null : tmp.getAsString();
        tmp = o.get("command");
        String command = (tmp == null || tmp.isJsonNull()) ? null : tmp.getAsString();
        tmp = o.get("player");
        String playerName = (tmp == null || tmp.isJsonNull()) ? null : tmp.getAsString();
        Plugin.log("responding to : " + playerName);
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
        if (command != null) {
            performCommand(playerName==null?null:plugin.getServer().getPlayer(playerName), command);
        }
    }

    /**
     * This is called every tick, and is where we do the work. We check the queue for messages,
     * and if there are any, we send them to the players in range.
     */
    private void update() {
        checkPurchaseTimer();   // check if we have a purchase timer that has expired, and if so, send the purchases to the AI.
        // check the queue - if there are any messages, speak them.
        while (!queue.isEmpty()) {
            String s = queue.poll();
            // parse the response
            // remove any backtick wrapping around the response.
            if (s.startsWith("```")) {
                s = s.substring(3, s.length() - 3);
            }
            // if the string starts with "json" remove it.
            if (s.startsWith("json")) {
                s = s.substring(4);
            }
            Plugin.log(npc.getName() +" returned JSON string is: " + s);
            JsonElement json;
            try {
                json = JsonParser.parseString(s);
            } catch (Exception e) {
                plugin.getLogger().severe("Error parsing JSON: " + e.getMessage());
                return;
            }

            if(json.isJsonArray()){
                for(JsonElement je : json.getAsJsonArray()){
                    processResponse(je);
                }
            } else {
                processResponse(json);
            }
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
     * Get the persona string from the plugin, applying templates as necessary. Only done
     * when a chat is created! It's probably expensive.
     * @param pname persona name
     * @return the processed persona string
     */
    private String getPersonaString(String pname) {
        Persona persona = Plugin.getInstance().personae.get(pname);
        String s;
        if (persona == null) {
            // if we don't have a persona, use the default.
            s = DEFAULT_PERSONA;
            if(gender==null)    // no gender given yet
                gender = plugin.defaultGender;  // there's no persona to get the default; use the plugin
        } else {
            if(gender==null)    // no gender given yet
                gender = persona.defaultGender; // we can get the gender from the persona
            s = persona.generateString(this);
        }
        return s;
    }

    void setPersona(String pname) {
        personaName = pname;
        chat = null; // a new chat will need to be made.
    }

    record NearbyPlayer(Player p,   // player
                        double d,   // distance in x and z
                        double dy   // distance in y
    ){}
    final Set<NearbyPlayer> emptySet  = new HashSet<>(); // avoids reinstantiations
    /**
     * Set of nearby visible players and distances - empty if no nearby player is a real player.
     */
    Set<NearbyPlayer> nearbyPlayers = emptySet;


    /**
     * Used to scan nearby entities for both players and mobs. Once a second should do it.
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
        Set<NearbyPlayer> r = new HashSet<>();
        boolean nonNPCPresent = false;
        Location myLocation = npc.getStoredLocation();

        // if we can't get the entity, we can't do anything. Perhaps because it just despawned?? That's
        // when I'm getting this error - just after NPC death events.
        if(npc.getEntity()==null)return;

        for (Entity e : npc.getEntity().getNearbyEntities(d, dy, d)) {
            if (e instanceof Player p) {
                if (!CitizensAPI.getNPCRegistry().isNPC(e))
                    nonNPCPresent = true;
                if (p.hasLineOfSight(npc.getEntity())) {
                    double dx = myLocation.getX() - p.getLocation().getX();
                    double dz = myLocation.getZ() - p.getLocation().getZ();
                    double dist = Math.sqrt(dx * dx + dz * dz);
                    double disty = myLocation.getY() - p.getLocation().getY();
                    r.add(new NearbyPlayer(p, dist, disty));
                    if(debug)
                        log_debug(String.format("%s scan ADDING %s (dist %.2f dy %.2f)",
                                npc.getEntity().getName(), p.getDisplayName(), dist, disty));
                }
            } else if(e instanceof Monster m){
                String mname = m.getName();
                double dist = m.getLocation().distance(npc.getEntity().getLocation());
                MonsterData nm = nearestMonster.get();
                if (nm == null || dist < nm.dist) {
                    nearestMonster.set(new MonsterData(mname, dist));
                    if(debug)log_debug(String.format("%s detected monster %s (dist %.2f)",
                            npc.getEntity().getName(), mname, dist));
                    if(m.hasLineOfSight(npc.getEntity())){
                        MonsterData nvm = nearestVisibleMonster.get();
                        if (nvm == null || dist < nvm.dist) {
                            nearestVisibleMonster.set(new MonsterData(mname, dist));
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
     * Add the inventory as a JSON array to a JsonObject, if we are carrying anything
     */
    private void appendInventory(JsonObject root) {
        JsonArray arr = new JsonArray();
        boolean isempty=true;
        if (npc.getEntity() instanceof Player p) {
            Inventory inv = p.getInventory();
            ItemStack[] items = inv.getContents();
            for (ItemStack item : items) {
                if (item != null) {
                    arr.add(item.getType().name());
                    isempty = false;
                }
            }
        }
        if(!isempty)
            root.add("inventory",arr);
    }



    /**
     * Part of the environment builder - append any combat data to a JsonObject
     */
    private void appendCombatData(JsonObject root) {
        Sentinel.SentinelData d = Plugin.getInstance().sentinelPlugin.makeData(npc);

        if(whenLastDamaged >= 0){
            // was this longer ago than a given duration?
            long lastDamageTime = (System.currentTimeMillis()-whenLastDamaged)/1000;
            if(lastDamageTime > plugin.attackNotificationDuration){
                whenLastDamaged = -1;
                root.addProperty("attacked", String.format("%s has not been attacked recently.",getNPC().getName()));
            } else {
                root.addProperty("attacked", String.format("%s was recently attacked by %s",
                        getNPC().getName(), whoDamagedBy.getName()));
            }
        }

        MonsterData nm = nearestMonster.get();
        MonsterData nvm = nearestVisibleMonster.get();
        if(nvm!=null) {
            root.addProperty("recently seen", nvm.m);
        } else if(nm!=null){
            root.addProperty("recently heard", nm.m);
        } else {
            root.addProperty("recently seen", "no monsters");
            root.addProperty("recently heard", "no monsters");
        }

        if (d != null) {
            // first, how long ago did we see combat
            double t = d.timeSinceAttack / 20.0; // convert to seconds
            log_debug("Time since attack " + t);
            if (t > 60) {
                root.addProperty("combat", String.format("%d minutes ago", (int) t / 60));
            } else if (t > 0) {
                root.addProperty("combat", String.format("%d seconds ago", (int) t));
            } else {
                root.addProperty("combat", plugin.getText("in-combat-now"));
            }
            // now, are we guarding someone?
            if (d.guarding != null)
                root.addProperty("guarding player", d.guarding);
            // health.
            double h = d.health;
            if (h >= 99.0) {
                root.addProperty("health", "maximum");
            } else {
                root.addProperty("health", String.format("%d%%", (int) h));
            }
        }
    }

    JsonObject prevContext = null;

    private static Set<Biome> biomesFromStrings(List<String> s) {
        return s.stream()
                .map(b -> {
                    try {
                        return Registry.BIOME.get(new NamespacedKey(Plugin.getInstance(), b));
                    } catch (IllegalArgumentException e) {
                        Plugin.log("Unknown biome: " + b);
                        return Biome.PLAINS;
                    }
                })
                .collect(Collectors.toSet());
    }

    final Set<Biome> coldBiomes = biomesFromStrings(List.of(
            "FROZEN_OCEAN", "DEEP_FROZEN_OCEAN", "SNOWY_BEACH", "SNOWY_PLAINS",
            "SNOWY_SLOPES", "SNOWY_TAIGA"));

    final Set<Biome> coldAbove100 = biomesFromStrings(List.of(
            "WINDSWEPT_GRAVELLY_HILLS", "WINDSWEPT_HILLS", "WINDSWEPT_FOREST", "STONY_SHORE",
            "DRIPSTONE_CAVES"
    ));

    final Set<Biome> coldAbove160 = biomesFromStrings(List.of(
            "TAIGA", "OLD_GROWTH_SPRUCE_TAIGA"
    ));

    final Set<Biome> coldAbove200 = biomesFromStrings(List.of(
            "OLD_GROWTH_PINE_TAIGA"
    ));


    /**
     * Get the context (environment, inventory etc.) as a JSON object, leaving out unchanged elements
     *
     * @return the context as a Json element, leaving out unchanged elements
     */
    private JsonElement getContext() {
        Location loc = npc.getStoredLocation();
        World w = loc.getWorld();

        Block blk = loc.getBlock();

        byte skyLight = blk.getLightFromSky();
        byte blockLight = blk.getLightFromBlocks();
        byte totalLight = blk.getLightLevel();

        JsonObject root = new JsonObject();

        if (skyLight == 0) {
            root.addProperty("time", plugin.getText("no-skylight-time"));
            root.addProperty("weather", plugin.getText("no-skylight-weather"));
        } else {
            long t = Objects.requireNonNull(w).getTime();
            int hours = (int) ((t / 1000 + 6) % 24);
            int minutes = (int) (60 * (t % 1000) / 1000);
            String timeString = String.format("%02d:%02d", hours, minutes);
            root.addProperty("time", timeString);

            // I need to tell if it's snow or rain.
            // this is a really rough method - it seems pretty impossible to do it properly.

            boolean isSnow = isSnow(loc);

            String weatherString = "clear";
            if (timeString.equals("midnight") || timeString.equals("night"))
                weatherString = "dark";
            else if (timeString.equals("dawn") || timeString.equals("dusk"))
                weatherString = "twilight";

            if (w.isThundering() && w.hasStorm()) {
                weatherString = "stormy and thundering";
            } else if (w.hasStorm()) {
                if (isSnow) {
                    weatherString = "snowing";
                } else {
                    weatherString = "raining";
                }
            }
            root.addProperty("weather", weatherString);
        }

        // add JCFUtils region data
        RegionManager rm = RegionManager.getManager(w);
        if (rm != null) {
            Region region = rm.getSmallestRegion(loc);
            if (region != null) {
                JsonObject regionObj = new JsonObject();
                regionObj.addProperty("name", region.name);
                if(!region.desc.isEmpty()){
                    regionObj.addProperty("description",region.desc);
                }
                root.add("region", regionObj);
            }
        }
        var nearbyWp = waypoints.getNearWaypoint(loc, 100);
        if(nearbyWp!=null){
            if(nearbyWp.distanceSquared() <16){
                root.addProperty("location", nearbyWp.name());
                root.addProperty("location description", nearbyWp.waypoint().desc);
            } else {
                root.addProperty("nearby location", nearbyWp.name());
                root.addProperty("nearby location description", nearbyWp.waypoint().desc);
            }
        }



        // who is nearby?
        if(!nearbyPlayers.isEmpty()) {
            JsonArray json = new JsonArray();

            var st = nearbyPlayers.stream()
                    .filter(p -> p.d < VERY_CLOSE_PLAYERS_DIST
                            && p.dy < VERY_CLOSE_PLAYERS_DISTY)      // quite close
                    .map(p -> ChatColor.stripColor(p.p.getDisplayName()));
            for(var s : st.toList()){
                json.add(s);
            }
            root.add("nearbyPlayers", json);
        }

        // light conditions?
        if(totalLight>0){
            root.addProperty("light from the sun", String.format("%d/15", skyLight));
            root.addProperty("light from lamps", String.format("%d/15", blockLight));

        } else {
            root.addProperty("light from the sun", "none");
            root.addProperty("light from lamps", "none");
        }

        // now, add the combat data - extra data will also be added if this is a Sentinel
        appendCombatData(root);
        // and the inventory
        appendInventory(root);

        JsonObject diffs = getDifferences(prevContext,root);
        prevContext = root;
        return diffs;
    }

    private boolean isSnow(Location loc) {
        boolean isSnow = false;  // if stormy, is it snow or rain?
        World w = loc.getWorld();
        if (w == null) return false;
        Biome b = w.getBiome(loc);

        // get altitude of npc
        double y = loc.getY();

        if( coldBiomes.contains(b)) {
            isSnow = true; // snow biome
        } else if (coldAbove200.contains(b) && y > 200) {
            isSnow = true; // above 200 in a cold biome
        } else if (coldAbove160.contains(b) && y > 160) {
            isSnow = true; // above 160 in a cold biome
        } else if (coldAbove100.contains(b) && y > 100) {
            isSnow = true; // above 100 in a cold biome
        }
        return isSnow;
    }

    // no sniggering at the back there. This gets a part from a text defined in the config.
    private Part getPartFromText(String textName){
        return Part.fromText(plugin.getText(textName));
    }

    /**
     * If the chat - the link to the upstream LLM - is null, create a new one setting
     * up the config and the system instructions. This is done when we respond to
     * a chat event for the first time.
     */
    private void createChatIfNull(){
        if (chat == null) {
            String personaString = getPersonaString(personaName);
            // generate the system instruction from the persona string.

            Content.Builder b = Content.builder();
            b.role("user");
            List<Part> parts = new ArrayList<>();
            parts.add(Part.fromText("Your name is " + npc.getFullName() + ". "));
            parts.add(getPartFromText("standard-system-instructions"));
            parts.add(Part.fromText(personaString));
            if(npc.hasTrait(SentinelTrait.class)){
                parts.add(getPartFromText("sentinel-instruction"));
            }
            if(isShop()){
                addShopInstructions(parts);
            }

            if(waypoints.getNumberOfWaypoints()>0){
                var locs = String.join(",", waypoints.getWaypointNames());
                parts.add(getPartFromText("go-instruction"));
                parts.add(Part.fromText(plugin.getText("location-list-start") + locs));
                for(String name : waypoints.getWaypointNames()) {
                    try {
                        var desc = waypoints.getWaypoint(name).desc;
                        parts.add(Part.fromText("\"" + name + "\": " + desc));
                    } catch (Waypoints.Exception e) {
                        plugin.getLogger().severe("Error getting waypoint description: " + e.getMessage());
                    }
                }
            }

            b.parts(parts);

            Content systemInstruction = b.build();
            if(plugin.showSystemInstructions)
                Plugin.log("System instruction for "+npc.getName()+" is "+ systemInstruction.toJson());

            // add this to the config.
            GenerateContentConfig config = GenerateContentConfig.builder()
                    .maxOutputTokens(256)   // we want quite short, conversational responses
                    .systemInstruction(systemInstruction)
                    .responseMimeType("application/json")
                    .build();
            // create new chat with the model specified in the plugin (which comes from
            // the configuration file).
            chat = plugin.client.chats.create(plugin.model, config);
            log_debug("NPC " + npc.getFullName() + " has been created with model " + plugin.model);
            log_debug("org.pale.gemininpc.Persona: " + personaString);
        }
    }

    static private String getItemStringFromShopActions(List<NPCShopAction> acts){
        var x = acts.stream().map(a -> a.describe()).toList();
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

    private void addInstructionsForShopPage(ShopTrait.NPCShopPage page, List<Part> parts) {
        // add the shop instructions
        // vile, vile, vile. There's no way of getting the item count.
        for(int i=0;i<45;i++){ // max items in a 5x9 shop. It's annoying, this.
            var item = page.getItem(i);
            if(item==null) continue; // item is null, skip it.
            var result = item.getResult();
            var cost = item.getCost();

            var resultMat = getItemMaterialFromShopActions(result);
            var costMat = getItemMaterialFromShopActions(cost);
            var displayMat = item.getDisplayItem(null).getType(); // this is the item that is displayed in the shop, not the result or cost.

            String itemString;
            if(costMat == displayMat){
                // if the item displayed in the shop is the COST item, we are buying this kind of thing.
                itemString = String.format("you buy %s for %s", getItemStringFromShopActions(cost),
                        getItemStringFromShopActions(result));
            } else {
                // otherwise, we are selling this kind of thing.
                itemString = String.format("you sell %s for %s", getItemStringFromShopActions(result),
                        getItemStringFromShopActions(cost));

            }
            Plugin.log("Adding item to shop instructions: " + itemString);
            parts.add(Part.fromText(itemString));
        }
    }

    private void addShopInstructions(List<Part> parts) {
        parts.add(getPartFromText("shop-instruction"));
        // add the shop items
        var shop = npc.getOrAddTrait(ShopTrait.class).getDefaultShop();
        String title = shop.getName();
        // we can only get the first page because for some reason there's no way of getting
        // the number of pages, and the code uses a getOrAdd.. pattern.

        var page = shop.getOrCreatePage(0);
        addInstructionsForShopPage(page, parts);
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
                output.add("context", getContext());
                output.add("input", new JsonPrimitive(input));

                String outString = output.toString();
                plugin.getServer().getLogger().info("Sending to AI: " + outString);
                plugin.request_count++;
                GenerateContentResponse response = chat.sendMessage(outString); // send to AI
                if (response == null) {
                    plugin.getServer().getLogger().severe("No response");
                    return;
                }
                String msg = response.text(); // will block?
                // put that in the queue
                // otherwise we're all good. Queue the message.
                queue.offer(msg);
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

    void pathTo(String name) throws Waypoints.Exception {
        navTarget = waypoints.pathTo(this, name);
    }

    /**
     * Destroy any existing chat session, forcing a reinitialise and complete local
     * memory loss!
     */
    void reset(){
        if(chat!=null){
            chat = null;
            prevContext = null;
        }
    }

    /**
     * Show debugging data
     */
    void showInfo(CallInfo c) {
        c.msg("NPC " + getNPC().getName());
        c.msg("  org.pale.gemininpc.Persona: " + personaName + "gender: "+gender);
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
