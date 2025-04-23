package org.pale.gemininpc;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.time.Instant;

import net.citizensnpcs.api.trait.TraitName;
import net.citizensnpcs.api.util.DataKey;

import org.bukkit.*;
import org.bukkit.block.Biome;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;


import com.google.genai.Chat;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;
import com.google.genai.types.Content;
import org.pale.gemininpc.plugininterfaces.NPCDestinations;
import org.pale.gemininpc.plugininterfaces.Sentinel;


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
     Each input will consist of lines of the form "username: text" or "environment: text". The "environment" lines
     describe your general environment, such as the weather and time of day. When asked about the time you should use
     this information. The other lines give the name of the user speaking to you and the conversation you should
     respond to.
     """.replaceAll("[\\t\\n\\r]+"," ");

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

    Map<String,Long> lastGreetedTime = new HashMap<>(); // last time we greeted a player
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

    // Here you should load up any values you have previously saved (optional).
    // This does NOT get called when applying the trait for the first time, only loading onto an existing npc at server start.
    // This is called AFTER onAttach so you can load defaults in onAttach, and they will be overridden here.
    // This is called BEFORE onSpawn, npc.getBukkitEntity() will return null.
    public void load(DataKey key) {
        // we load the entire persona string.
        personaName = key.getString("pname", "default");
    }

    // Save settings for this NPC (optional). These values will be persisted to the Citizens saves file
    public void save(DataKey key) {
        key.setString("pname", personaName);
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
     *  Run code when your trait is attached to a NPC.
     *  This is called BEFORE onSpawn, so npc.getBukkitEntity() will return null
     *  This would be a good place to load configurable defaults for new NPCs.
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
        Plugin.log(" Despawn run on " + npc.getFullName());

        // remove this NPC from the plugin's set of NPCs which have the trait
        plugin.removeChatter(npc);
    }

    /**
     * Run code when the NPC is spawned. Note that npc.getBukkitEntity() will be null until this method is called.
     * This is called AFTER onAttach and AFTER Load when the server is started.
     */
    @Override
    public void onSpawn() {
        Plugin.log(" Spawn run on " + npc.getFullName());
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
     * This is called every tick, and is where we do the work. We check the queue for messages,
     * and if there are any, we send them to the players in range.
     */
    private void update() {
        // check the queue - if there are any messages, speak them.
        while(!queue.isEmpty()) {
            String s = queue.poll();
            if (s != null) { // it's a double check, yes.
                for (Player p : getNearPlayers(20.0)) {
                    Plugin.log("message in queue : " + s);
                    p.sendMessage(s);
                }
            } else {
                Plugin.log("queue is empty or null msg");
            }
        }
        processGreet();
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
        // note the 1 - we have to be roughly on the same level, AND we have to be able to see them.
        // Actually, we check to see if they can see *us*.
        for (Entity e : npc.getEntity().getNearbyEntities(d, 1, d)) {
            if (e instanceof Player) {
                Player p = (Player) e;
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
        return r;
    }

    /**
     * Part of the environment builder - append any combat data
     * @return
     */
    private void appendCombatString(StringBuilder sb) {
        Sentinel.SentinelData d = plugin.getInstance().sentinelPlugin.makeData(npc);
        if(d == null)
            return;
        // first, how long ago did we see combat
        double t = d.timeSinceAttack / 20; // convert to seconds
        if (t > 60) {
            sb.append("You have not seen combat for ").append((int) t / 60).append(" minutes.\n");
        } else if (t > 0) {
            sb.append("You have not seen combat for ").append((int) t).append(" seconds.\n");
        } else {
            sb.append("You are in combat!\n");
        }
        // now, are we guarding someone?
        if (d.guarding != null) {
            sb.append("You are guarding ").append(d.guarding).append(".\n");
        } else {
            sb.append("You are not guarding anyone.\n");
        }
        // health.
        double h = d.health;
        if (h >= 99.0) {
            sb.append("You are at full health.\n");
        } else {
            sb.append("You are at ").append((int) h).append("% health.\n");
        }

    }

    String prevEnv = ""; // previous weather string
    /**
     * Get the weather as a string preceded by "environment: " if it has changed. Return the empty string
     * otherwise
     * @return the weather as a string or empty string
     */
    private String getEnvironmentString(){
        StringBuilder sb = new StringBuilder().append("environment: ");
        World w = npc.getEntity().getLocation().getWorld();

        if(npc.getEntity().getLocation().getBlock().getLightFromSky() == 0){
            sb.append("You are underground and do not know the time or weather.\n");
        } else {

            long t = Objects.requireNonNull(w).getTime();
            /* Commented out because it produces unreliable results when an NPC tries to be more accurate.
            For example, saying "mid-morning approaching noon" when it's just "day".

            // get time of day as a string - dawn, morning, noon, afternoon, evening, nighttime
            String timeString = "day";
            if (t > 22700 || t <= 450) {
                timeString="dawn";
            } else if (t > 4000 && t <= 8000) {
                timeString="noon";
            } else if (t > 11500 && t <= 13500) {
                timeString="dusk";
            } else if (t > 16000 && t <= 20000) {
                timeString="midnight";
            } else if (t > 12000) {
                timeString="night";
            }
            */
            int hours = (int) ((t / 1000 + 6) % 24);
            int minutes = (int) (60 * (t % 1000) / 1000);
            String timeString = String.format("%02d:%02d", hours, minutes);
            sb.append("The time is ").append(timeString).append(". The weather is ");

            // I need to tell if it's snow or rain.
            // this is a really rough method - it seems pretty impossible to do it properly.
            Biome b = w.getBiome(npc.getEntity().getLocation());
            boolean isSnow = false;  // if stormy, is it snow or rain?
            // get altitude of npc
            double y = npc.getEntity().getLocation().getY();
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
            sb.append(weatherString).append(".\n");
        }

        // now, add the combat data if this is a Sentinel
        appendCombatString(sb);

        String name = npc.getFullName();

        if (!prevEnv.contentEquals(sb)) {
            plugin.getLogger().info(name+"  Env changed: " + sb);
            prevEnv = sb.toString();
            return prevEnv;
        }
        plugin.getLogger().info(name+"  Env unchanged: " + sb);
        return ""; // no change
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
        plugin.getServer().getLogger().info(player.getName() + " has been spoken to!");

        // if the chat session is null, we need to create it.
        if (chat == null) {
            String personaString = getPersonaString(personaName);
            // generate the system instruction from the persona string.
            Content systemInstruction = Content.fromParts(
                    Part.fromText("Your name is " + npc.getFullName() + ". "),
                    Part.fromText(STANDARD_INSTRUCTIONS),
                    Part.fromText(personaString));
            // add this to the config.
            GenerateContentConfig config = GenerateContentConfig.builder()
                    .maxOutputTokens(256)   // we want quite short, conversational responses
                    .systemInstruction(systemInstruction)
                    .build();
            // create new chat with the model specified in the plugin (which comes from
            // the configuration file).
            chat = plugin.client.chats.create(plugin.model, config);
            plugin.getLogger().info("NPC " + npc.getFullName() + " has been created with model " + plugin.model);
            plugin.getLogger().info("Persona: " + personaString);
        }

        // look for nearby players, and only do something if there are some.
        Set<Player> q = getNearPlayers(10); // audible distance
        plugin.getServer().getLogger().info("Players nearby :" + q);
        if (!q.isEmpty()) {
            String toName = ChatColor.stripColor(player.getDisplayName());
            // start a new thread which sends to the AI and waits for the result
            new Thread(() -> {
                // build the input string
                StringBuilder sb = new StringBuilder().append(getEnvironmentString());
                sb.append(toName).append(": ").append(input).append("\n");
                plugin.getServer().getLogger().info("Sending to AI: " + sb);
                GenerateContentResponse response = chat.sendMessage(sb.toString()); // send to AI
                if (response == null) {
                    plugin.getServer().getLogger().severe("No response");
                    return;
                }
                plugin.getServer().getLogger().info("Message has been returned");
                String msg = response.text(); // will block?
                plugin.getServer().getLogger().info(msg);
                String s = ChatColor.AQUA + "[" + npc.getFullName() + " -> " + toName + "] " + ChatColor.WHITE + msg;
                // put that in the queue
                // otherwise we're all good. Queue the message.
                queue.offer(s);
            }).start();
        }
    }

    private void processGreet(){
        Set<Player> players = getNearPlayers(10);
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
}
