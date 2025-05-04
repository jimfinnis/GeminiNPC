
package org.pale.gemininpc;

import com.google.genai.Client;
import net.citizensnpcs.api.CitizensAPI;

import java.util.*;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.Paths;

import net.citizensnpcs.api.npc.NPC;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.util.Vector;
import org.bukkit.Location;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;


import org.jetbrains.annotations.NotNull;
import org.pale.gemininpc.Command.*;
import org.pale.gemininpc.plugininterfaces.NPCDestinations;
import org.pale.gemininpc.plugininterfaces.Sentinel;
import org.pale.gemininpc.waypoints.Waypoints;


public class Plugin extends JavaPlugin implements Listener {
    /**
     * Make the plugin a weird singleton.
     */
    static Plugin instance = null;
    static final String ROOTCMDNAME = "gemini";
    public String model; // the model to use for Gemini AI - visible for the trait
    public Client client; // the client for the AI API - visible for the trait
    EventRateTracker eventRateTracker = new EventRateTracker();

    private final Registry commandRegistry = new Registry(ROOTCMDNAME);

    // interfaces to other plugins
    NPCDestinations ndPlugin;
    Sentinel sentinelPlugin;

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

    public static void log(String msg) {
        getInstance().getLogger().info(msg);
    }

    public static void warn(String msg) {
        getInstance().getLogger().warning(msg);
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

        if (getServer().getPluginManager().getPlugin("Citizens") == null
                || !Objects.requireNonNull(getServer()
                        .getPluginManager()
                        .getPlugin("Citizens"))
                .isEnabled()) {
            getLogger().severe("Citizens 2.0 not found or not enabled");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // construct interfaces to other plugins
        ndPlugin = new NPCDestinations();
        sentinelPlugin = new Sentinel();

        //Register.
        net.citizensnpcs.api.CitizensAPI
                .getTraitFactory()
                .registerTrait(net.citizensnpcs.api.trait.TraitInfo.create(GeminiNPCTrait.class));

        // if there isn't a config file, create one from the default that gets compiled into the jar (it's in
        // the project as config.yml)
        saveDefaultConfig();


        // register the commands automatically - these are tagged with @Cmd.
        commandRegistry.register(this); // register commands

        // load configuration data
        FileConfiguration c = this.getConfig();
        final ConfigurationSection deflt = c.getConfigurationSection("main");
        if(deflt==null)
            throw new RuntimeException("No main section in config");
        // get the api key and model for Gemini AI from the config
        String apiKey = deflt.getString("apikey", "NOKEY");
        model = deflt.getString("model", "gemini-2.0-flash-lite");

        // load the AI personae that are available
        loadPersonae(c);

        // create the gen AI API client
        client = Client.builder().apiKey(apiKey).build();

        // this is the listener for pretty much ALL events EXCEPT NPC events, not just chat.
        new ChatEventListener(this);
        getLogger().info("GeminiNPC has been enabled");
    }

    // This is a map of persona name onto the persona string loaded from each file.
    public Map<String, String> personae = new HashMap<>();

    public void loadPersonae(FileConfiguration c) {
        // load all of the personae - these are name:filename pairs, paths relative
        // to the Minecraft server directory. Each file contains a set of system
        // instructions for the AI.
        // get the common persona data - this is a set of instructions that are common to all -
        // and prepend to the data for each persona.
        ConfigurationSection ps = c.getConfigurationSection("main");
        String common = Objects.requireNonNull(ps).getString("common", "");
        if(!common.isEmpty()){
            try {
                Path p = Paths.get(common);
                common = Files.readString(p);
            } catch (Exception e) {
                warn("CANNOT READ COMMON PERSONA " + common);
                common = "";
            }
        }
        log("Common persona data is " + common.length() + " chars");
        List<String> ss = c.getStringList("persona-directories");
        if(ss!=null){
            for(String s:ss){
                Path p = Paths.get(s);
                if(!p.isAbsolute()){
                    p = Paths.get(getServer().getWorldContainer().getAbsolutePath(),s);
                }
                if(!p.toFile().exists()){
                    warn("Persona directory "+s+" does not exist");
                } else {
                    log("Persona directory is "+p.toString());
                }
                // get all the files in the directory
                try {
                    String finalCommon = common;
                    Files.list(p).forEach(f -> {
                        String name = f.getFileName().toString();
                        try {
                            String data = Files.readString(f);
                            personae.put(name, finalCommon + "\n" + data);
                            log("Loaded persona : " + name);
                        } catch (Exception e) {
                            warn("CANNOT READ PERSONA " + name);
                        }
                    });
                } catch (Exception e) {
                    warn("CANNOT READ PERSONA DIRECTORY " + s);
                }
            }
        } else {
            log("No persona directories in config");
        }
    }

    // Handy function to send a message to the command sender.
    public static void sendCmdMessage(CommandSender s, String msg) {
        s.sendMessage(ChatColor.AQUA + "[GeminiNPC] " + ChatColor.YELLOW + msg);
    }

    /**
     * Called when a "gemini" command is given. This is the main command for the
     * plugin.
     *
     * @param sender the sender of the command
     * @param command the command
     * @param label the command label
     * @param args the arguments
     * @return true if the command was handled, false otherwise
     */
    @Override
    public boolean onCommand(@NotNull CommandSender sender,
                             @NotNull Command command,
                             @NotNull String label,
                             @NotNull String[] args) {
        String cn = command.getName();
        if (cn.equals(ROOTCMDNAME)) {
            commandRegistry.handleCommand(sender, args);
            return true;
        }
        return false;
    }

    // this is a list of all the NPCs which have the trait
    Set<NPC> chatters = new HashSet<>();

    public void addChatter(NPC npc) {
        chatters.add(npc);
    }

    public void removeChatter(NPC npc) {
        chatters.remove(npc);
    }

    // get the trait for the selected NPC for a player - each player can select an NPC
    // with the "npc select" command.
    public static GeminiNPCTrait getTraitFor(CommandSender sender) {
        return getTraitFor(CitizensAPI.getDefaultNPCSelector().getSelected(sender));
    }

    public static GeminiNPCTrait getTraitFor(Entity e){
        if(e instanceof Player){
            NPC npc = CitizensAPI.getNPCRegistry().getNPC(e);
            if(npc!=null){
                return getTraitFor(npc);
            }
        }
        return null;
    }

    // given an NPC, get the GeminiNPCTrait for it.
    public static GeminiNPCTrait getTraitFor(NPC npc) {
        if (npc == null) {
            return null;
        }
        if (npc.hasTrait(GeminiNPCTrait.class)) {
            return npc.getOrAddTrait(GeminiNPCTrait.class);
        }
        return null;

    }



    public static boolean isNear(Location a, Location b, double dist) {
        return (a.distance(b) < dist && Math.abs(a.getY() - b.getY()) < 2);
    }

    /**
     * This is the main message handler - it gets called when a player sends a chat
     * message. We check to see if we are roughly facing an NPC which has the trait,
     * and if so, we call that NPC's respondTo method. That's the only place where
     * the AI is called.
     *
     * @param player the player who sent the message
     * @param msg  the message they sent
     */
    public void handleMessage(Player player, String msg) {
        Location playerloc = player.getLocation();
        Vector playerpos = playerloc.toVector();
        Vector playerdir = playerloc.getDirection().normalize();
        for (NPC npc : chatters) {
            Location npcl = npc.getEntity().getLocation();
            Vector npcpos = npcl.toVector();
            if (npc.hasTrait(GeminiNPCTrait.class)) {
                if (isNear(playerloc, npcl, 5)) { // chatters assume <5m and you're talking to them.
                    Vector tonpc = npcpos.subtract(playerpos).normalize();
                    // dot prod of facing vector and vector to player
                    double dot = tonpc.dot(playerdir);
                    //log("Dot to "+npc.getName()+ " is "+Double.toString(dot));
                    // make sure we're roughly facing the NPC
                    if (dot > 0.2) {
                        // this is where the magic happens. As it were.
                        GeminiNPCTrait ct = npc.getOrAddTrait(GeminiNPCTrait.class);
                        ct.respondTo(player, msg);
                    }
                }
            }
        }
    }

    public String getPersonaString(String persona) {
        return personae.getOrDefault(persona, null);
    }

    /**
     * Commands
     */

    @Cmd(desc = "set the persona for an NPC", argc = 1, cz = true)
    public void persona(CallInfo c) {
        String persona = c.getArgs()[0];
        GeminiNPCTrait t = c.getCitizen();
        if (personae.containsKey(persona)) {
            t.setPersona(persona);
        } else {
            Plugin.warn("No persona " + persona + " exists");
            c.msg("Persona " + ChatColor.RED + persona + ChatColor.YELLOW
                    + " does not exist");
        }
        c.msg("Set persona to " + ChatColor.AQUA + persona);
    }


    @Cmd(desc = "show help for a command or list commands", usage = "[<command name>]")
    public void help(CallInfo c) {
        if (c.getArgs().length == 0) {
            commandRegistry.listCommands(c);
        } else {
            commandRegistry.showHelp(c, c.getArgs()[0]);
        }
    }

    @Cmd(desc = "reload all personae (will reinitialise all chats)", argc = 0)
    public void reload(CallInfo c) {
        reloadConfig();
        FileConfiguration cc = this.getConfig();
        loadPersonae(cc);
        for (String name : personae.keySet()) {
            c.msg("Loaded " + ChatColor.AQUA + " " + name);
        }
    }

    @Cmd(desc = "list all available personae", argc = 0)
    public void list(CallInfo c) {
        c.msg("Available personae:");
        for (String name : personae.keySet()) {
            c.msg(ChatColor.AQUA + name);
        }
    }

    @Cmd(desc="list all NPCs with a persona", argc=0)
    public void npcs(CallInfo c) {
        c.msg("NPCs with a persona:");
        for (NPC npc : chatters) {
            GeminiNPCTrait t = getTraitFor(npc);
            c.msg(ChatColor.AQUA + npc.getName()+" : "+t.personaName);
        }
    }

    @Cmd(desc="Get general resource usage info", argc=0)
    public void usage(CallInfo c) {
        c.msg("GeminiNPC usage:");
        c.msg("  Events in last minute: "+eventRateTracker.getEventsInLastMinute());
        c.msg("  Active chatters: "+chatters.size());
        c.msg("  Personae: "+personae.size());
        c.msg("  NPCs with personae: "+chatters.size());
    }

    @Cmd(desc="Get info on an NPC", argc=0, cz=true)
    public void info(CallInfo c){
        GeminiNPCTrait t = c.getCitizen();
        c.msg("NPC "+t.getNPC().getName());
        c.msg("  Persona: "+t.personaName);
        c.msg("  Waypoints:");
        for(String name:t.waypoints.getWaypointNames()) {
            try {
                Waypoints.Waypoint w = t.waypoints.getWaypoint(name);
                c.msg("    " + name + " : " + w.toString());
            } catch (Waypoints.Exception e) {
                c.msg("    " + name + " : ERROR: " + e.getMessage());
            }
        }
    }

    @Cmd(desc="Set a waypoint for the current NPC at your current location", argc=-1, usage="[name] [desc..]", cz=true)
    public void wp(CallInfo c){
        String name = c.getArgs()[0];
        StringBuilder sb = new StringBuilder();
        int len = c.getArgs().length;
        for(int i=1;i<len;i++){
            sb.append(c.getArgs()[i]);
            if(i<len-1)
                sb.append(" ");
        }
        String desc = sb.toString();
        GeminiNPCTrait t = c.getCitizen();
        t.waypoints.add(name, desc, c.getPlayer().getLocation());
        c.msg(ChatColor.AQUA+"Waypoint "+name+" added at "+
                c.getPlayer().getLocation().getBlockX()+","+
                c.getPlayer().getLocation().getBlockY()+","+
                c.getPlayer().getLocation().getBlockZ());
    }

    @Cmd(desc="Delete a named waypoint", argc=1, usage="[name]", cz=true)
    public void wpdel(CallInfo c){
        String name = c.getArgs()[0];
        GeminiNPCTrait t = c.getCitizen();
        try {
            t.waypoints.delete(name);
        } catch (Waypoints.Exception e) {
            c.msg(ChatColor.RED+"Waypoint path error: "+e.getMessage());
        }
    }

    @Cmd(desc="Make an NPC path to the named waypoint", argc=1, usage="[name]", cz=true)
    public void go(CallInfo c){
        String name = c.getArgs()[0];
        GeminiNPCTrait t = c.getCitizen();
        try {
            t.pathTo(name);
            t.navCompletionHandler = (String s) -> {
                c.msg(ChatColor.AQUA+"path to "+name+" completed: "+s);
            };
        } catch (Waypoints.Exception e) {
            c.msg(ChatColor.RED+"Waypoint path error: "+e.getMessage());
        }
    }
}
