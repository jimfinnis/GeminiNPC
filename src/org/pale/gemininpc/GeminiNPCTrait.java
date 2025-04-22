package org.pale.gemininpc;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.time.Instant;
import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.trait.TraitName;
import net.citizensnpcs.api.util.DataKey;

import org.bukkit.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;


import com.google.genai.Client;
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

    public GeminiNPCTrait() {
        super("gemininpc");
        plugin = JavaPlugin.getPlugin(Plugin.class);
        client = Client.builder().apiKey(plugin.apiKey).build();
    }

    Plugin plugin = null;
    private int tickint=0;
    public long timeSpawned=0;
    public long timeStateStarted = 0;

    // this is the queue the responses arrive in.
    ConcurrentLinkedQueue<String> queue = new ConcurrentLinkedQueue<>();

    Client client;
    Chat chat = null; // will be created the first time you chat
    // this is the system instruction that will be sent.
    static final String DEFAULT_PERSONA = "You have no memory of who or what you are.";
    String personaString = DEFAULT_PERSONA;

    // Here you should load up any values you have previously saved (optional).
    // This does NOT get called when applying the trait for the first time, only loading onto an existing npc at server start.
    // This is called AFTER onAttach so you can load defaults in onAttach and they will be overridden here.
    // This is called BEFORE onSpawn, npc.getBukkitEntity() will return null.
    public void load(DataKey key) {
        personaString = key.getString("persona",DEFAULT_PERSONA);
    }

    // Save settings for this NPC (optional). These values will be persisted to the Citizens saves file
    public void save(DataKey key) {
        key.setString("persona",personaString);
    }

    // Called every tick
    @Override
          public void run() {
              if(tickint++==20){ // to reduce CPU usage - this is about 1Hz.
                  update();
                  tickint=0;
              }
              timeSpawned++;
          }

    //Run code when your trait is attached to a NPC.
    //This is called BEFORE onSpawn, so npc.getBukkitEntity() will return null
    //This would be a good place to load configurable defaults for new NPCs.
    @Override
          public void onAttach() {
              plugin.getServer().getLogger().info(npc.getName() + " has been assigned GeminiNPC!");

          }
    

    // Run code when the NPC is despawned. This is called before the entity actually despawns so npc.getBukkitEntity() is still valid.
    @Override
          public void onDespawn() {
              Plugin.log(" Despawn run on "+npc.getFullName());
              plugin.removeChatter(npc);
          }

    //Run code when the NPC is spawned. Note that npc.getBukkitEntity() will be null until this method is called.
    //This is called AFTER onAttach and AFTER Load when the server is started.
    @Override
          public void onSpawn() {
              Plugin.log(" Spawn run on "+npc.getFullName());
              plugin.addChatter(npc);
          }

    //run code when the NPC is removed. Use this to tear down any repeating tasks.
    @Override
          public void onRemove() {
          }

    int idleTime;

    private void update(){
      // check the queue
      String s = queue.poll();
      if(s!=null){
        for(Player p: getNearPlayers(10.0)){
          Plugin.log("message in queue : " +s);
          p.sendMessage(s);
        }
      }
    }
    
    void setPersona(String ps){
        personaString = ps;
        chat = null; // a new chat will need to be made.
    }
              
        

    /**
     * Time at which we last saw a player, given their nick. Yes, you
     * can disguise yourself by changing nick.
     */
    Map<String, Instant> playerLastSawTime = new HashMap<String, Instant>();

    List<Player> getNearPlayers(double d){
        List<Player> r = new ArrayList<Player>();
        // note the 1 - we have to be roughly on the same level, AND we have to be able to see them.
        // Actually, we check to see if they can see *us*.
        for(Entity e: npc.getEntity().getNearbyEntities(d,1,d)){
            if(e instanceof Player){
                Player p = (Player)e;
                // now, I'm going to recycle this bit of code so we can store
                // when we last saw a player! We only "see" a player when we try
                // to talk, which is semantically odd, but it should work.
                playerLastSawTime.put(p.getName().toLowerCase(), Instant.now());
                if(p.hasLineOfSight(npc.getEntity())) {
                    r.add(p);
                    //Plugin.log("ADDING: "+p.getDisplayName());
                }
            }
        }
        return r;
    }


    private void say(final String toName,final String input){
        List<Player> q = getNearPlayers(10); // audible distance
        plugin.getServer().getLogger().info("Players nearby :" + q);
        if(q.size()>0){
            // start a new thread waiting for the result
            new Thread(new Runnable () {
              public void run(){
                plugin.getServer().getLogger().info("Sending to AI: "+input);
                GenerateContentResponse response = chat.sendMessage(input); // send to AI
                if(response == null){
                  plugin.getServer().getLogger().severe("No response");
                  return;
                }
                plugin.getServer().getLogger().info("Message has been returned");
                String msg = response.text(); // will block
                plugin.getServer().getLogger().info(msg);
                String s = ChatColor.AQUA+"["+npc.getFullName()+" -> "+toName+"] "+ChatColor.WHITE+msg;
                // put that in the queue
                // otherwise we're all good. Queue the message.
                queue.offer(s);

              }
            }).start();
          }
    }


    public void respondTo(Player player,String input) {
        plugin.getServer().getLogger().info(player.getName() + " has been spoken to!");
        
        if(chat==null){ // no chat session; create one from the persona string
            Content systemInstruction = Content.fromParts(Part.fromText(personaString));
            GenerateContentConfig config = GenerateContentConfig.builder()
                  .maxOutputTokens(1024)
                  .systemInstruction(systemInstruction)
                  .build();
        
            chat = client.chats.create(plugin.model,config);
        }

        say(player.getDisplayName(),input);
    }

}
