
package org.pale.gemininpc;

import net.citizensnpcs.api.CitizensAPI;

import java.util.*;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.Paths;

import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.bukkit.Location;
import org.bukkit.DyeColor;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.plugin.java.JavaPlugin;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;


import org.pale.gemininpc.Command.*;


public class Plugin extends JavaPlugin {
    public static void log(String msg) {
        getInstance().getLogger().info(msg);
    }

    public static void warn(String msg) {
        getInstance().getLogger().warning(msg);
    }

    /**
     * Make the plugin a weird singleton.
     */
    static Plugin instance = null;
    static final String ROOTCMDNAME="gemini";
    public String apiKey;
    public String model;
    
    private Registry commandRegistry = new Registry(ROOTCMDNAME);

    /**
     * Use this to get plugin instances - don't play silly buggers creating new
     * ones all over the place!
     */
    public static Plugin getInstance() {
        if (instance == null)
            throw new RuntimeException(
                    "Attempt to get plugin when it's not enabled");
        return instance;
    }

    @Override
    public void onDisable() {
        instance = null;
        getLogger().info("GeminiNPC has been disabled");
    }

    public Plugin() {
        super();
        if (instance != null)
            throw new RuntimeException("oi! only one instance!");
    }

    @Override
    public void onEnable() {
        instance = this;
        //check if Citizens is present and enabled.

        if (getServer().getPluginManager().getPlugin("Citizens") == null || getServer().getPluginManager().getPlugin("Citizens").isEnabled() == false) {
            getLogger().severe("Citizens 2.0 not found or not enabled");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        //Register.
        net.citizensnpcs.api.CitizensAPI.getTraitFactory().registerTrait(net.citizensnpcs.api.trait.TraitInfo.create(GeminiNPCTrait.class));

        saveDefaultConfig();
        commandRegistry.register(this); // register commands
        
        
        // config. 
        FileConfiguration c = this.getConfig();
        final ConfigurationSection deflt = c.getConfigurationSection("main");
        apiKey = deflt.getString("apikey");
        model = deflt.getString("model");
        loadPersonae(c);

        // this is the listener for pretty much ALL events EXCEPT NPC events, not just chat.
        new ChatEventListener(this);
        getLogger().info("GeminiNPC has been enabled");
    }
    
    // map of persona name -> system instruction fed to AI
    public Map<String,String> personae = new HashMap<>();
    
    public void loadPersonae(FileConfiguration c) {
        // load all of the personae - these are name:filename pairs, paths relative
        // to the server directory
        final ConfigurationSection ps = c.getConfigurationSection("personae");
        if(ps==null)
            throw new RuntimeException("No personae section in config");
            
        for(String name: ps.getKeys(false)){
            // get filename
            try {
                String fn  = ps.getString(name);
                Path p = Paths.get(fn);
                String data = Files.readString(p);
                personae.put(name,data);
                log("Loaded persona : "+name);
            } catch(Exception e) {
                warn("CANNOT READ PERSONA "+name);
            }
        }
    }
            
            
              
        


    public static void sendCmdMessage(CommandSender s, String msg) {
        s.sendMessage(ChatColor.AQUA + "[GeminiNPC] " + ChatColor.YELLOW + msg);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command,
                             String label, String[] args) {
        String cn = command.getName();
        if (cn.equals(ROOTCMDNAME)) {
            commandRegistry.handleCommand(sender, args);
            return true;
        }
        return false;
    }

    public static GeminiNPCTrait getTraitFor(NPC npc) {
        if (npc == null) {
            return null;
        }
        if (npc.hasTrait(GeminiNPCTrait.class)) {
            return npc.getTrait(GeminiNPCTrait.class);
        }
        return null;

    }

    Set<NPC> chatters = new HashSet<NPC>();

    public void addChatter(NPC npc) {
        chatters.add(npc);
    }
    public void removeChatter(NPC npc){
        chatters.remove(npc);
    }
    
    public static GeminiNPCTrait getTraitFor(CommandSender sender) {
        return getTraitFor(CitizensAPI.getDefaultNPCSelector().getSelected(sender));
    }

    @EventHandler
    public void onNavigationComplete(net.citizensnpcs.api.ai.event.NavigationCompleteEvent e){
        Bukkit.broadcastMessage("Nav complete");
    }

    public static boolean isNear(Location a,Location b,double dist){
        return (a.distance(b)<5 && Math.abs(a.getY()-b.getY())<2);
    }

    public void handleMessage(Player player, String msg){
        Location playerloc = player.getLocation();
        Vector playerpos = playerloc.toVector();
        Vector playerdir = playerloc.getDirection().normalize();
        for(NPC npc: chatters){
            Location npcl = npc.getEntity().getLocation();
            Vector npcpos = npcl.toVector();
            if(npc.hasTrait(GeminiNPCTrait.class)){
                if(isNear(playerloc,npcl,2)){ // chatters assume <2m and you're talking to them.
                    Vector tonpc = npcpos.subtract(playerpos).normalize();
                    // dot prod of facing vector and vector to player
                    double dot = tonpc.dot(playerdir);
                    //log("Dot to "+npc.getName()+ " is "+Double.toString(dot));
                    // make sure we're roughly facing the NPC
                    if(dot>0.8){
                        GeminiNPCTrait ct = npc.getTrait(GeminiNPCTrait.class);
                        ct.respondTo(player,msg);
                    }
                }
            }
        }
    }

    /**
     * Commands
     */
    
    @Cmd(desc = "set the persona for an NPC", argc = 1,cz=true)
    public void persona(CallInfo c) {
        String persona = c.getArgs()[0];
        GeminiNPCTrait t = c.getCitizen();
        if(personae.containsKey(persona)){
            t.setPersona(personae.get(persona));
        } else {
            Plugin.warn("No persona "+persona+" exists");
        }
    }
    
    
    @Cmd(desc = "show help for a command or list commands", argc = -1, usage = "[<command name>]")
    public void help(CallInfo c) {
        if (c.getArgs().length == 0) {
            commandRegistry.listCommands(c);
        } else {
            commandRegistry.showHelp(c, c.getArgs()[0]);
        }
    }
    
    @Cmd(desc = "reload personae",argc=0)
    public void reload(CallInfo c) {
        FileConfiguration cc = this.getConfig();
        loadPersonae(cc);
        for(String name: personae.keySet()){
            c.msg("Loaded "+ChatColor.AQUA+" "+name);
        }
    }

}
