package org.pale.gemininpc;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.time.Instant;
import java.util.stream.Collectors;

import com.google.gson.*;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.ai.Navigator;
import net.citizensnpcs.api.trait.TraitName;
import net.citizensnpcs.api.util.DataKey;

import org.bukkit.*;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
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
import org.pale.gemininpc.plugininterfaces.Sentinel;
import org.pale.gemininpc.waypoints.Waypoints;
import org.pale.jcfutils.region.Region;
import org.pale.jcfutils.region.RegionManager;

import static org.pale.gemininpc.Utils.getDifferences;


//This is your trait that will be applied to a npc using the /trait mytraitname command.
//Each NPC gets its own instance of this class.
//the Trait class has a reference to the attached NPC class through the protected field 'npc' or getNPC().
//The Trait class also implements Listener so you can add EventHandlers directly to your trait.
@TraitName("gemininpc") // convenience annotation in recent CitizensAPI versions for specifying trait name
public class GeminiNPCTrait extends net.citizensnpcs.api.trait.Trait {

    // This string is a block of standard instructions that describe the rules of the conversation. It precedes the
    // persona string, and is used to set the context for the AI. It is sent to the AI when the chat is first
    // created.
    static final String STANDARD_INSTRUCTIONS =
            """
                    Each input will be in this form:
                    context: {
                        environment: string describing your environment (time, weather, etc)
                        inventory: list of items you have
                    }
                    input: {
                        player name: the input string from the player to which you should respond
                        OR
                        event: an event that occurred
                    }
                    The environment and inventory may be missing, in which case they are unchanged.
                    
                    Use this JSON schema for the output:
                    
                        Output = {'player': str, 'response': str, 'command': str}
                    
                    'player' is the name of the player to whom you are responding. It may be empty if you
                    are responding to an event.
                    
                    The command is optional and should be used rarely. The "give ITEM" command will give an item
                    to the player you are responding to. ITEM must be a Minecraft material.
                    
                    Within the reponse string, actions should be in third person and in brackets, like this: (He smiles quietly). Use this
                    rarely. Always write from your point of view.
                    """;//.replaceAll("[\\t\\n\\r]+"," ");

    /**
     * Initialise the trait.
     */
    public GeminiNPCTrait() {
        super("gemininpc");
        plugin = JavaPlugin.getPlugin(Plugin.class);
    }

    Plugin plugin;           // useful pointer back to the plugin shared by all traits
    private int tickint = 0;        // a counter to slow down updates
    public long timeSpawned = 0;    // ticks since spawn
    Location navTarget;     // current path destination using our waypoints (not Chatcitizen's) or null

    Map<String, Long> lastGreetedTime = new HashMap<>(); // last time we greeted a player
    Set<Player> nearPlayersForGreet = new HashSet<>(); // players nearby

    // The "chat" part of the GenAI api is synchronous, so we use a queue and a thread to
    // make it non-blocking. Requests are sent to the AI in a thread, and when the response
    // is returned it is added to this queue. We don't need to store the player, because the
    // NPC will just "say" the response to all players in range. The queue is read inside the
    // update method by polling - not ideal, but it's very quick and the update infrequent.
    ConcurrentLinkedQueue<String> queue = new ConcurrentLinkedQueue<>();

    // this is the Chat API object - it's created the first time you call the respondTo method,
    // or when it's called after you change the persona (which sets this to zero)
    Chat chat = null; // will be created the first time you chat
    // this is the system instruction that will be sent. It's the "default" personality. Yes, it
    // can be creepy as heck.
    static final String DEFAULT_PERSONA = "You have no memory of who or what you are.";

    String personaName = "default"; // the name of the persona

    Waypoints waypoints = new Waypoints();

    // we can set one of these up to be called when navigation completes (or fails)
    public interface NavCompletionFunction {
        void call(String code);
    }
    NavCompletionFunction navCompletionHandler;



    // Here you should load up any values you have previously saved (optional).
    // This does NOT get called when applying the trait for the first time, only loading onto an existing npc at server start.
    // This is called AFTER onAttach so you can load defaults in onAttach, and they will be overridden here.
    // This is called BEFORE onSpawn, npc.getBukkitEntity() will return null.
    public void load(DataKey key) {
        // we load the entire persona string.
        personaName = key.getString("pname", "default");
        waypoints.load(key);
    }

    // Save settings for this NPC (optional). These values will be persisted to the Citizens saves file
    public void save(DataKey key) {
        key.setString("pname", personaName);
        waypoints.save(key);
    }

    // Called every tick
    @Override
    public void run() {
        if (tickint++ == 10) { // to reduce CPU usage - this is about 0.5Hz.
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
        respondTo(null, "(you killed a " + mobname + ")");
    }

    /**
     * This is called when a player right-clicks on an NPC. The held item is transferred into the NPCs
     * inventory, and the respondTo function is called with a special message.
     */
    void give(Player p) {
        ItemStack st = p.getInventory().getItemInMainHand();
        Material mat = st.getType();
        if (mat == Material.AIR) return;     // nothing to take

        if (npc.getEntity() instanceof Player) {
            // we can only give to player-type npcs. For others, the item will just disappear.
            // first we add to the NPC.
            Player npcp = (Player) npc.getEntity();
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
        int newAmount = st.getAmount() - 1;
        if (newAmount <= 0) {
            p.getInventory().setItemInMainHand(null);
        } else {
            st.setAmount(newAmount);
        }

        // and send the message to the AI
        respondTo(p, "(gives you " + st.getType().name() + ")");
    }

    /**
     * There's a command in the reponse and we should run it.
     *
     * @param p       the player in the reponse (i.e. who we are responding to)
     * @param command the command
     */
    private void performCommand(Player p, String command) {
        boolean hasSentinel = npc.hasTrait(SentinelTrait.class);
        Sentinel s = plugin.getInstance().sentinelPlugin;
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
                    if (npc.getEntity() instanceof Player) {
                        Player npcp = (Player) npc.getEntity();
                        Inventory inv = npcp.getInventory();
                        inv.removeItem(st);
                    }
                } else {
                    p.sendMessage(ChatColor.RED + npc.getFullName() + " tried to give you " + st.getType().name() + " but your inventory is full.");
                }
            } else {
                plugin.log("Bad material name in command: " + command);
            }
        }
        else if(command.startsWith("setguard")){
            if(hasSentinel){
                String name = command.substring(9).trim();
                if(name.equalsIgnoreCase("none")){
                    s.setGuard(npc, null);
                    plugin.log("NPC " + npc.getFullName() + " unguarded.");
                } else {
                    Player p2 = plugin.getServer().getPlayer(name);
                    if(p2 != null) {
                        s.setGuard(npc, p2.getUniqueId());
                        plugin.log("NPC " + npc.getFullName() + " is now guarding " + p2.getDisplayName());
                    } else {
                        plugin.log("Cannot find player to guard: " + name);
                    }
                }
            } else {
                plugin.log("NPC " + npc.getFullName() + " does not have Sentinel.");
            }
        } else if(command.startsWith("unguard")){
            if(hasSentinel){
                s.setGuard(npc, null);
                plugin.log("NPC " + npc.getFullName() + " unguarded.");
            } else {
                plugin.log("NPC " + npc.getFullName() + " does not have Sentinel.");
            }
        } else if(command.startsWith("go ")){
            String name = command.substring(3).trim();
            if(name.equalsIgnoreCase("none")){
                npc.getNavigator().cancelNavigation();
                plugin.log("NPC " + npc.getFullName() + " got a 'go none'.");
            } else {
                try {
                    pathTo(name);
                    plugin.log("NPC " + npc.getFullName() + " is now going to waypoint "+name);
                } catch(Waypoints.Exception e) {
                    plugin.log("Cannot find waypoint: " + name);
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
            for (Player p : getNearPlayers(20.0)) {
                // Plugin.log("message in queue : " + s);
                String outmsg;
                if(playerName==null || playerName.isEmpty())
                    outmsg = ChatColor.AQUA + "[" + npc.getFullName() + "] " + ChatColor.WHITE + response;
                else
                    outmsg = ChatColor.AQUA + "[" + npc.getFullName() + " -> " + playerName + "] " + ChatColor.WHITE + response;
                p.sendMessage(outmsg);
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
            Plugin.log("JSON string is: " + s);
            JsonElement json;
            try {
                json = new JsonParser().parse(s);
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
        processGreet();

        // check navigation completion or timeout
        if(navTarget!=null) {
            Plugin.log("Checking navigation completion for "+npc.getName());
            String navCompletionCode=null;
            Navigator nav = npc.getNavigator();
            if(npc.getStoredLocation().distance(navTarget) < 2){
                // close enough!
                nav.cancelNavigation();
                navCompletionCode = "arrived close to destination";
                navTarget = null;
            } else if(!nav.isNavigating()){
                navCompletionCode = "arrived at destination";
                navTarget = null;
            }
            if(navCompletionHandler!=null && navCompletionCode!=null){
                navCompletionHandler.call(navCompletionCode);
                navCompletionHandler = null;
            }
            if(navTarget==null){
                Plugin.log("Nav was completed");
            }
        }
    }

    private String getPersonaString(String pname) {
        // get the persona string from the plugin
        String s = Plugin.getInstance().getPersonaString(pname);
        if (s == null) {
            // if we don't have a persona, use the default.
            s = DEFAULT_PERSONA;
        }
        return s;
    }

    void setPersona(String pname) {
        personaName = pname;
        chat = null; // a new chat will need to be made.
    }


    /**
     * Time at which we last saw a player, given their nick. Yes, you
     * can disguise yourself by changing nick.
     */
    Map<String, Instant> playerLastSawTime = new HashMap<>();

    /**
     * Get a list of the players near this NPC. This is used to determine who we should
     * send chat messages to. It also resets the last time we saw a player, which can
     * be useful.
     */
    Set<Player> getNearPlayers(double d) {
        Set<Player> r = new HashSet<>();
        boolean nonNPCPresent = false;
        // note the 1 - we have to be roughly on the same level, AND we have to be able to see them.
        // Actually, we check to see if they can see *us*.
        for (Entity e : npc.getEntity().getNearbyEntities(d, 1, d)) {
            if (e instanceof Player) {
                Player p = (Player) e;
                if (!CitizensAPI.getNPCRegistry().isNPC(e))
                    nonNPCPresent = true;
                // now, I'm going to recycle this bit of code so we can store
                // when we last saw a player! We only "see" a player when we try
                // to talk, which is semantically odd, but it should work.
                playerLastSawTime.put(p.getName().toLowerCase(), Instant.now());
                if (p.hasLineOfSight(npc.getEntity())) {
                    r.add(p);
                    //Plugin.log("ADDING: "+p.getDisplayName());
                }
            }
        }
        // if there are no *real* players nearby, don't waste AI tokens on greeting.
        if (nonNPCPresent)
            return r;
        else
            return new HashSet<>();
    }

    /**
     * Add the inventory as a JSON array to a JsonObject, if we are carrying anything
     * @return JSON array
     */
    private void appendInventory(JsonObject root) {
        JsonArray arr = new JsonArray();
        boolean isempty=true;
        if (npc.getEntity() instanceof Player) {
            Player p = (Player) npc.getEntity();
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
        Sentinel.SentinelData d = plugin.getInstance().sentinelPlugin.makeData(npc);
        if (d == null)
            return;
        // first, how long ago did we see combat
        double t = d.timeSinceAttack / 20.0; // convert to seconds
        if (t > 60) {
            root.addProperty("combat", String.format("%d minutes ago", (int) t / 60));
        } else if (t > 0) {
            root.addProperty("combat", String.format("%d seconds ago", (int) t));
        } else {
            root.addProperty("combat", "now");
        }
        // now, are we guarding someone?
        if (d.guarding != null)
            root.addProperty("guarding player", d.guarding);
        // health.
        double h = d.health;
        if (h >= 99.0) {
            root.addProperty("health","maximum");
        } else {
            root.addProperty("health", String.format("%d%%", (int)h));
        }
    }

    JsonObject prevContext = null;

    // we have to do a getNearbyEntities query in the main thread, so the trait holds onto this for use
    // in getContext. It also gets reused in a few places.

    Set<Player> nearPlayers;



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
            root.addProperty("time", "You are underground and do not know the time.");
            root.addProperty("weather", "You are underground and do not know the weather");
        } else {
            long t = Objects.requireNonNull(w).getTime();
            int hours = (int) ((t / 1000 + 6) % 24);
            int minutes = (int) (60 * (t % 1000) / 1000);
            String timeString = String.format("%02d:%02d", hours, minutes);
            root.addProperty("time", timeString);

            // I need to tell if it's snow or rain.
            // this is a really rough method - it seems pretty impossible to do it properly.
            Biome b = w.getBiome(loc);
            boolean isSnow = false;  // if stormy, is it snow or rain?
            // get altitude of npc
            double y = loc.getY();
            switch (b) {  // ugh.
                case FROZEN_OCEAN, DEEP_FROZEN_OCEAN, SNOWY_BEACH, SNOWY_PLAINS, SNOWY_SLOPES, SNOWY_TAIGA:
                    isSnow = true;
                    break;
                case WINDSWEPT_GRAVELLY_HILLS, WINDSWEPT_HILLS, WINDSWEPT_FOREST, STONY_SHORE, DRIPSTONE_CAVES:
                    if (y > 120.0) isSnow = true;
                    break;
                case TAIGA, OLD_GROWTH_SPRUCE_TAIGA:
                    if (y > 160.0) isSnow = true;
                    break;
                case OLD_GROWTH_PINE_TAIGA:
                    if (y > 200.0) isSnow = true;
                    break;
                default:
                    break;
            }
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
            if(nearbyWp.distanceSquared<16){
                root.addProperty("location", nearbyWp.name);
                root.addProperty("location description", nearbyWp.waypoint.desc);
            } else {
                root.addProperty("nearby location",nearbyWp.name);
                root.addProperty("nearby location description",nearbyWp.waypoint.desc);
            }
        }



        // who is nearby?
        if(!nearPlayers.isEmpty()) {
            JsonArray nearbyPlayers = new JsonArray();
            var st = nearPlayers.stream()
                    .map(Player::getDisplayName)
                    .map(ChatColor::stripColor);
            for(var s : st.collect(Collectors.toList())){
                nearbyPlayers.add(s);
            }
            root.add("nearbyPlayers", nearbyPlayers);
        }

        // light conditions?
        if(totalLight>0){
            root.addProperty("light", String.format("%d/15", totalLight));
            root.addProperty("skylight", String.format("%d/15", skyLight));
            root.addProperty("lamplight", String.format("%d/15", blockLight));
        } else {
            root.addProperty("light", "dark");
        }

        // now, add the combat data if this is a Sentinel
        appendCombatData(root);
        // and the inventory
        appendInventory(root);

        String name = npc.getFullName();

        JsonObject diffs = getDifferences(prevContext,root);
        prevContext = root;
        return diffs;
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
            parts.add(Part.fromText(STANDARD_INSTRUCTIONS));
            parts.add(Part.fromText(personaString));
            if(npc.hasTrait(SentinelTrait.class)){
                parts.add(Part.fromText("You can send the 'setguard playername' command, which will make you follow and guard a player. You can also send the 'unguard' command, which will stop you following and guarding a player."));
            }

            if(waypoints.getNumberOfWaypoints()>0){
                var locs = waypoints.getWaypointNames().stream().collect(Collectors.joining(","));
                parts.add(Part.fromText("To go to a location, use the 'go locationname' command"));
                parts.add(Part.fromText("Locations you can go to: " + locs));
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
            plugin.getLogger().info("NPC " + npc.getFullName() + " has been created with model " + plugin.model);
            plugin.getLogger().info("Persona: " + personaString);
        }
    }


    /**
     * This is called when the NPC is spoken to. It will be called from the
     * ChatEventListener when a player sends a message. We check to see if the
     * player is in range, and if so, we send the message to the AI in a thread.
     * The response will be added to a queue which is read in the update. That
     * makes this effectively non-blocking.
     *
     * @param player The player who spoke to the NPC.
     * @param input  The message they sent.
     */
    public void respondTo(Player player, String input) {
        var l = plugin.getServer().getLogger();
        if(player==null)
            l.info("NPC " + npc.getFullName() + " is responding to an event");
        else
            l.info(player.getName() + " has been spoken to!");

        // if the chat session is null, we need to create it.
        createChatIfNull();

        // limit rate globally - across all chats!
        if(plugin.eventRateTracker.getEventsInLastMinute()>20){
            l.info("Rate limit exceeded, not responding to " + player.getDisplayName());
            return;
        }

        // look for nearby players, and only do something if there are some.
        nearPlayers = getNearPlayers(12); // audible distance
        if (!nearPlayers.isEmpty()) {
            String toName = (player==null)?"event":ChatColor.stripColor(player.getDisplayName());
            // start a new thread which sends to the AI and waits for the result
            new Thread(() -> {
                plugin.eventRateTracker.event();

                JsonObject output = new JsonObject();
                output.add("context", getContext());
                output.add("input", new JsonPrimitive(input));

                String outString = output.toString();
                plugin.getServer().getLogger().info("Sending to AI: " + outString);
                GenerateContentResponse response = chat.sendMessage(outString); // send to AI
                if (response == null) {
                    plugin.getServer().getLogger().severe("No response");
                    return;
                }
                plugin.getServer().getLogger().info("Message has been returned");
                String msg = response.text(); // will block?
                plugin.getServer().getLogger().info(msg);
                // put that in the queue
                // otherwise we're all good. Queue the message.
                queue.offer(msg);
            }).start();
        }
    }

    private void processGreet() {
        Set<Player> players = getNearPlayers(10); // shorter range than nearPlayers?
        // pick one who isn't in the "near players for greet" list - i.e. who has just turned up
        for (Player p : players) {
            if (!nearPlayersForGreet.contains(p)) {
                // make sure we haven't greeted them recently
                Long lastGreet = lastGreetedTime.get(p.getName().toLowerCase());
                if (lastGreet == null || (System.currentTimeMillis() - lastGreet) > 60000) { // 1 minute
                    // greet them by passing a special input to respondTo
                    respondTo(p, "(enters)");
                    nearPlayersForGreet.add(p);
                    // remember when we greeted them
                    lastGreetedTime.put(p.getName().toLowerCase(), System.currentTimeMillis());
                }
            }
        }
        nearPlayersForGreet = players; // update the list of players we have greeted
    }

    void pathTo(String name) throws Waypoints.Exception {
        navTarget = waypoints.pathTo(this, name);
    }
}
