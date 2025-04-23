package org.pale.gemininpc;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.time.Instant;

import net.citizensnpcs.api.trait.TraitName;
import net.citizensnpcs.api.util.DataKey;

import org.bukkit.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;


import com.google.genai.Chat;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;
import com.google.genai.types.Content;


//This is your trait that will be applied to a npc using the /trait mytraitname command.
//Each NPC gets its own instance of this class.
//the Trait class has a reference to the attached NPC class through the protected field 'npc' or getNPC().
//The Trait class also implements Listener so you can add EventHandlers directly to your trait.
@TraitName("gemininpc") // convenience annotation in recent CitizensAPI versions for specifying trait name
public class GeminiNPCTrait extends net.citizensnpcs.api.trait.Trait {

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

    // this is the persona string that will be used to create the chat session - it's sent as a
    // "system instruction".
    String personaString = DEFAULT_PERSONA;

    // Here you should load up any values you have previously saved (optional).
    // This does NOT get called when applying the trait for the first time, only loading onto an existing npc at server start.
    // This is called AFTER onAttach so you can load defaults in onAttach, and they will be overridden here.
    // This is called BEFORE onSpawn, npc.getBukkitEntity() will return null.
    public void load(DataKey key) {
        // we load the entire persona string.
        personaString = key.getString("persona", DEFAULT_PERSONA);
    }

    // Save settings for this NPC (optional). These values will be persisted to the Citizens saves file
    public void save(DataKey key) {
        key.setString("persona", personaString);
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
                for (Player p : getNearPlayers(10.0)) {
                    Plugin.log("message in queue : " + s);
                    p.sendMessage(s);
                }
            }
        }
    }

    void setPersona(String ps) {
        personaString = ps;
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
    List<Player> getNearPlayers(double d) {
        List<Player> r = new ArrayList<>();
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
            // generate the system instruction from the persona string.
            Content systemInstruction = Content.fromParts(Part.fromText(personaString));
            // add this to the config.
            GenerateContentConfig config = GenerateContentConfig.builder()
                    .maxOutputTokens(1024)
                    .systemInstruction(systemInstruction)
                    .build();
            // create new chat with the model specified in the plugin (which comes from
            // the configuration file).
            chat = plugin.client.chats.create(plugin.model, config);
        }

        // look for nearby players, and only do something if there are some.
        List<Player> q = getNearPlayers(10); // audible distance
        plugin.getServer().getLogger().info("Players nearby :" + q);
        if (!q.isEmpty()) {
            String toName = player.getDisplayName();
            // start a new thread which sends to the AI and waits for the result
            new Thread(() -> {
                plugin.getServer().getLogger().info("Sending to AI: " + input);
                GenerateContentResponse response = chat.sendMessage(input); // send to AI
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

}
