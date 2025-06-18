
package org.pale.gemininpc;

import com.google.genai.Client;
import net.citizensnpcs.api.CitizensAPI;

import java.io.IOException;
import java.util.*;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.stream.Stream;

import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Bukkit;
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
import org.pale.gemininpc.command.*;
import org.pale.gemininpc.plugininterfaces.Sentinel;
import org.pale.gemininpc.waypoints.Waypoint;
import org.pale.gemininpc.waypoints.Waypoints;


public class Plugin extends JavaPlugin implements Listener {
    /**
     * Make the plugin a weird singleton.
     */
    static Plugin instance = null;
    static final String ROOTCMDNAME = "gemini";
    public String model; // the model to use for Gemini AI - visible for the trait
    public Client client; // the client for the AI API - visible for the trait
    final EventRateTracker eventRateTracker = new EventRateTracker();
    int sched;  // scheduler handle
    int request_count = 0;   // AI request ctr
    boolean showSystemInstructions; // config option
    int attackNotificationDuration; // config option - how many seconds does the "you have been attacked by.." last
    boolean callsEnabled = true;    // use to disable calls to Gemini LLM model

    // how long we wait after a purchase before saying "no more purchases incoming, let's group
    // them and process them."
    long purchaseTimeout = 3000; // milliseconds

    // the default probability that one of our NPCs will respond to something said by another NPC,
    // as opposed to a player
    double defaultNPCRespondProb = 0;

    private final Registry commandRegistry = new Registry(ROOTCMDNAME);
    static final int TICK_RATE = 20;
    public String defaultGender = "non-binary";
    // interfaces to other plugins
    Sentinel sentinelPlugin;

    /**
     * Use this to get plugin instances - don't play silly buggers creating new
     * ones all over the place!
     */
    public static Plugin getInstance() {
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

        // load configuration data - this part of the config CAN'T be reloaded. Sorry.
        FileConfiguration c = this.getConfig();
        final ConfigurationSection deflt = c.getConfigurationSection("main");
        if(deflt==null)
            throw new RuntimeException("No main section in config");
        // get the api key and model for Gemini AI from the config
        String apiKey = deflt.getString("apikey", "NOKEY");
        model = deflt.getString("model", "gemini-2.0-flash-lite");

        // loads all the config data, including personae
        loadConfig(c);

        // create the gen AI API client
        client = Client.builder().apiKey(apiKey).build();

        // this is the listener for pretty much ALL events EXCEPT NPC events, not just chat.
        new ChatEventListener(this);

        // let's start an update for the entire plugin. This will periodically run a special update
        // on each trait that lets it do AI stuff without user input. Dangerous.
        if(!Bukkit.getScheduler().isCurrentlyRunning(sched)){
            sched = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, () -> {
                // pick a random "chatter"
                if(!chatters.isEmpty()) {
                    NPC npc = chatters.stream()
                            .skip(new Random().nextInt(chatters.size()))
                            .findFirst().orElse(null);
                    if (npc != null) {
                        GeminiNPCTrait t = getTraitFor(npc);
                        if (t != null)
                            t.updateInfrequent();
                    }
                }
            }, TICK_RATE*60, TICK_RATE*30); // every 30 seconds we update one AI. Delay of 1 min before we start
            // If an AI has been updated recently, this won't happen because the trait will rate-limit
            // on a per-NPC basis. That way we don't get single-NPC worlds constantly updating 1 AI.
        }

        getLogger().info("GeminiNPC has been enabled");
    }

    // This is a map of persona name onto the persona string loaded from each file.
    public final Map<String, Persona> personae = new HashMap<>();
    // This is a map of common texts used in the plugin - not common templates; these are just variables
    // and can't contain templates themselves.
    private final Map<String, String> texts = new HashMap<>();
    /**
     * These are the variables in the template system which can be replaced, and their
     * replacement values - often strings but sometimes not (e.g. lists of strings are permitted).
     * They are applied to persona files when the persona is set on the NPC.
     */
    final Map<String, Object> templateValues = new HashMap<>();

    /**
     * Used to read the contents of a file and warn if it fails, returning a string
     * @param f the file path
     * @param defaultString the string to use if we failed to load
     * @return either the file contents or the default string
     */
    public String readFile(Path f, String defaultString){
        String s = defaultString;
        try {
            s = Files.readString(f);
        } catch (Exception e) {
            getLogger().severe("CANNOT READ " + f);
        }
        return s;
    }

    public interface FileProcessor {
        void run(String nameOfFile, Path path);
    }
    /**
     * Given a config parameter "dirlist_name", if it exists in the configuration section,
     * open the directory of that name and process the texts of all the files therein with
     * a function.
     * @param cs     configuration section
     * @param dirListName  name of directory list element in config section
     * @param function  function to process files through
     */
    public void processFilesInConfigDirectory(ConfigurationSection cs, String dirListName,
                                              FileProcessor function){
        List<String> dirlist = cs.getStringList(dirListName);
        for(String s: dirlist){
            Path p = Paths.get(s);
            if(!p.isAbsolute()){
                p = Paths.get(getServer().getWorldContainer().getAbsolutePath(),s);
            }
            if(!p.toFile().exists()){
                warn(dirListName+" directory "+s+" does not exist");
            } else {
                log(dirListName+" directory: "+p);
            }
            try(Stream<Path> entries = Files.list(p)) {
                // for each file in the directory, run the function
                entries.forEach(f -> {
                    String name = f.getFileName().toString();
                    function.run(name, f);
                });
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }


    public void loadConfig(FileConfiguration c) {
        // load all of the personae - these are name:filename pairs, paths relative
        // to the Minecraft server directory. Each file contains a set of system
        // instructions for the AI.

        ConfigurationSection ps = c.getConfigurationSection("main");
        if (ps == null) {
            throw new RuntimeException("No main section in config");
        }

        defaultGender = ps.getString("default-gender","non-binary");
        showSystemInstructions = ps.getBoolean("show-system-instructions", false);
        attackNotificationDuration = ps.getInt("attack-notification-duration", 20);
        purchaseTimeout = ps.getLong("purchase-timeout", 3000L); // default 3 seconds

        defaultNPCRespondProb = ps.getDouble("default-npc-respond-prob", 0.1);

        // load the common templates - these can contain template variables themselves {{like}} {{this}}, and
        // can be included in all persona templates with {{include "common_template_name"}}.

        Map<String, String> commonTemplates = new HashMap<>();
        processFilesInConfigDirectory(c, "common-template-directories",
                (name, file) -> {
                    String template = readFile(file, "");
                    if (template != null) {
                        // add the template to the template values
                        commonTemplates.put(name, template);
                    }
                });
        ConfigurationSection common_section = c.getConfigurationSection("common-templates");
        if (common_section != null) {
            for (String key : common_section.getKeys(false)) {
                String value = common_section.getString(key);
                if (value != null) {
                    commonTemplates.put(key, value);
                }
            }
        }
        // then tell the persona system that the template loader should contain those templates
        Persona.initialiseTemplateLoader(commonTemplates);

        // now load the special template values - these are texts that used as variables in the persona data

        // First, for each file in the directories listed, add the contents of that file as a template value.
        processFilesInConfigDirectory(c, "template-value-directories",
                (name, file)->{
                    String template = readFile(file, "");
                    if(template!=null)templateValues.put(name, template);
                });

        // Then add some other template values from the main
        ConfigurationSection template_values = c.getConfigurationSection("template-values");
        if (template_values != null) {
            for (String key : template_values.getKeys(false)) {
                templateValues.put(key, template_values.get(key));
            }
        }

        // and load the personae, not using the template values just yet!
        //
        processFilesInConfigDirectory(c, "persona-directories",
                (name, path) -> {
                    Persona p = new Persona(name, path);
                    personae.put(p.name, p);
                });

        // finally pull in the common texts, which are in the "texts" section.
        ConfigurationSection cs = c.getConfigurationSection("texts");
        if (cs != null) {
            for (String key : cs.getKeys(false)) {
                String value = cs.getString(key);
                if (value != null) {
                    texts.put(key, value);
                }
            }
        }
    }

    /**
     * Return one of the standard texts, or "text-.." if it's not found
     * @param name name of text
     */
    public String getText(String name){
        if (texts.containsKey(name)) {
            return texts.get(name);
        } else {
            getLogger().severe("Cannot find text: "+name);
            return "TEXT-" + name;
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
    final Set<NPC> chatters = new HashSet<>();

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



    public static boolean isNear(Location a, Location b, double dist, double ydist) {
        return (a.distance(b) < dist && Math.abs(a.getY() - b.getY()) < ydist);
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
            Location npc_location = npc.getEntity().getLocation();
            Vector npcpos = npc_location.toVector();
            if (npc.hasTrait(GeminiNPCTrait.class)) {
                if (isNear(playerloc, npc_location, 5, 3)) { // chatters assume <5m and you're talking to them.
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

    /**
     * Commands
     */

    @SuppressWarnings("unused")
    @Cmd(desc = "set the persona for an NPC", argc = 1, cz = true)
    public void persona(CallInfo c) {
        String persona = c.getArgs()[0];
        GeminiNPCTrait t = c.getCitizen();
        if (personae.containsKey(persona)) {
            t.setPersona(persona);
        } else {
            Plugin.warn("No persona " + persona + " exists");
            c.msg("org.pale.gemininpc.Persona " + ChatColor.RED + persona + ChatColor.YELLOW
                    + " does not exist");
        }
        c.msg("Set persona to " + ChatColor.AQUA + persona);
    }


    @SuppressWarnings("unused")
    @Cmd(desc = "show help for a command or list commands", usage = "[<command name>]")
    public void help(CallInfo c) {
        if (c.getArgs().length == 0) {
            commandRegistry.listCommands(c);
        } else {
            commandRegistry.showHelp(c, c.getArgs()[0]);
        }
    }

    @SuppressWarnings("unused")
    @Cmd(desc = "reload all config data and personae (will reinitialise all chats)", argc = 0)
    public void reload(CallInfo c) {
        reloadConfig();
        FileConfiguration cc = this.getConfig();
        loadConfig(cc);
        for (String name : personae.keySet()) {
            c.msg("Loaded " + ChatColor.AQUA + " " + name);
        }

        // reinit
        for(NPC npc: chatters){
            GeminiNPCTrait t = getTraitFor(npc);
            t.reset();
        }
    }

    @SuppressWarnings("unused")
    @Cmd(desc = "list all available personae", argc = 0)
    public void list(CallInfo c) {
        c.msg("Available personae:");
        for (String name : personae.keySet()) {
            c.msg(ChatColor.AQUA + name);
        }
    }

    @SuppressWarnings("unused")
    @Cmd(desc="list all NPCs with a persona", argc=0)
    public void npcs(CallInfo c) {
        c.msg("NPCs with a persona:");
        for (NPC npc : chatters) {
            GeminiNPCTrait t = getTraitFor(npc);
            c.msg(ChatColor.AQUA + npc.getName()+" : "+t.personaName +" ("+t.gender+")");
        }
    }

    @SuppressWarnings("unused")
    @Cmd(desc="Get general resource usage info", argc=0)
    public void usage(CallInfo c) {
        c.msg("GeminiNPC usage:");
        c.msg("  Events in last minute: "+eventRateTracker.getEventsInLastMinute());
        c.msg("  Active chatters: "+chatters.size());
        c.msg("  Personae: "+personae.size());
        c.msg("  NPCs with personae: "+chatters.size());
    }

    @SuppressWarnings("unused")
    @Cmd(desc="Get info on an NPC", argc=0, cz=true)
    public void info(CallInfo c){
        c.getCitizen().showInfo(c);
    }

    /**
     * assuming the description starts with the second argument, concatenate remaining arguments into
     * a single string
     * @param c the call info
     * @return a single string
     */
    private static String getdesc(CallInfo c){
        StringBuilder sb = new StringBuilder();
        int len = c.getArgs().length;
        for(int i=1;i<len;i++){
            sb.append(c.getArgs()[i]);
            if(i<len-1)
                sb.append(" ");
        }
        return sb.toString();
    }

    @SuppressWarnings("unused")
    @Cmd(desc="Set a waypoint for the current NPC at your current location", usage="[name] [desc..]", cz=true)
    public void wp(CallInfo c){
        String name = c.getArgs()[0];
        GeminiNPCTrait t = c.getCitizen();
        t.waypoints.add(name, getdesc(c), c.getPlayer().getLocation());
        c.msg(ChatColor.AQUA+"Waypoint "+name+" added at "+
                c.getPlayer().getLocation().getBlockX()+","+
                c.getPlayer().getLocation().getBlockY()+","+
                c.getPlayer().getLocation().getBlockZ());
    }

    @SuppressWarnings("unused")
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

    @SuppressWarnings("unused")
    @Cmd(desc="Change an NPC waypoint to the player's location", argc=1, usage="[name]", cz=true)
    public void wploc(CallInfo c){
        String name = c.getArgs()[0];
        GeminiNPCTrait t = c.getCitizen();
        try {
            Waypoint wp = t.waypoints.getWaypoint(name);
            wp.setLocation(c.getPlayer().getLocation());
            c.msg(ChatColor.AQUA+"Waypoint "+name+" moved to "+
                    c.getPlayer().getLocation().getBlockX()+","+
                    c.getPlayer().getLocation().getBlockY()+","+
                    c.getPlayer().getLocation().getBlockZ());
        } catch (Waypoints.Exception e) {
            c.msg(ChatColor.RED+"Waypoint error: "+e.getMessage());
        }
    }

    @SuppressWarnings("unused")
    @Cmd(desc="Change an NPC waypoint description", usage="[name] [desc...]", cz=true)
    public void wpdesc(CallInfo c){
        String name = c.getArgs()[0];
        GeminiNPCTrait t = c.getCitizen();
        try {
            Waypoint wp = t.waypoints.getWaypoint(name);
            wp.desc = getdesc(c);
        } catch (Waypoints.Exception e) {
            c.msg(ChatColor.RED+"Waypoint error: "+e.getMessage());
        }
    }

    @SuppressWarnings("unused")
    @Cmd(desc="Make an NPC path to the named waypoint", argc=1, usage="[name]", cz=true)
    public void go(CallInfo c){
        String name = c.getArgs()[0];
        GeminiNPCTrait t = c.getCitizen();
        try {
            t.pathTo(name);
            t.navCompletionHandler = (code, dist) ->
                        c.msg(ChatColor.AQUA+"path to "+name+" completed: "+code.label+", dist="+dist);
            c.msg("Navigating to "+name+": "+t.waypoints.getWaypoint(name).toString());
        } catch (Waypoints.Exception e) {
            c.msg(ChatColor.RED+"Waypoint path error: "+e.getMessage());
        }
    }

    @SuppressWarnings("unused")
    @Cmd(desc="show number of API requests made",argc=0)
    public void reqs(CallInfo c){
        c.msg(ChatColor.AQUA+"Requests total since boot: "+request_count);
        c.msg(ChatColor.AQUA+"Requests in last minute: "+eventRateTracker.getEventsInLastMinute());
    }

    @SuppressWarnings("unused")
    @Cmd(desc="toggle debugging for NPC (mainly paths)", cz=true, argc=0)
    public void debug(CallInfo c){
        GeminiNPCTrait t = c.getCitizen();
        if(t!=null){
            t.debug = !t.debug;
            c.msg(ChatColor.AQUA+"Debugging "+t.debug+" for "+t.getNPC().getName());
        }
    }

    @SuppressWarnings("unused")
    @Cmd(desc="temporarily disable calls to the AI model",argc=0)
    public void disable(CallInfo c){
        c.msg(ChatColor.AQUA+"Disabling AI model calls");
        callsEnabled = false;
    }

    @SuppressWarnings("unused")
    @Cmd(desc="enable calls to the AI model",argc=0)
    public void enable(CallInfo c){
        c.msg(ChatColor.AQUA+"Enable AI model calls");
        callsEnabled = true;
    }

    @SuppressWarnings("unused")
    @Cmd(desc="set the gender", cz=true,argc=1)
    public void gender(CallInfo c){
        String gender = c.getArgs()[0];
        c.msg(ChatColor.AQUA+"Setting gender to "+gender);
        c.getCitizen().setGender(gender);
    }

    @SuppressWarnings("unused")
    @Cmd(desc="set the chance (0-1) that this NPC will respond to another NPC's message", cz=true, argc=1)
    public void setrespond(CallInfo c) {
        String arg = c.getArgs()[0];
        try {
            double prob = Double.parseDouble(arg);
            if(prob<0 || prob>1){
                c.msg(ChatColor.RED+"Probability must be between 0 and 1");
                return;
            }
            GeminiNPCTrait t = c.getCitizen();
            t.npcRespondProb = prob;
            c.msg(ChatColor.AQUA+"Set NPC respond probability to "+prob);
        } catch (NumberFormatException e) {
            c.msg(ChatColor.RED+"Invalid probability: "+arg);
        }
    }

    @SuppressWarnings("unused")
    @Cmd(desc="quick set of persona and/or gender, e.g. qs Boris g=male p=soldier1")
    public void qs(CallInfo c){
        String[] args = c.getArgs();
        if(args.length<1){
            c.msg(ChatColor.RED+"need at least an NPC name");
            return;
        }
        String name = args[0];
        GeminiNPCTrait t = chatters.stream()
                .filter(npc -> npc.getName().equals(name))
                .findFirst()
                .map(Plugin::getTraitFor)
                .orElse(null);
        if(t==null){
            c.msg(ChatColor.RED+"No such NPC "+name);
            return;
        }

        for(int i=1;i<args.length;i++){
            String arg = args[i];
            switch (arg.substring(0, 2)) {
                case "p=" -> {
                    String persona = arg.substring(2);
                    if (personae.containsKey(persona)) {
                        t.setPersona(persona);
                    } else {
                        Plugin.warn("No persona " + persona + " exists");
                        c.msg("org.pale.gemininpc.Persona " + ChatColor.RED + persona + ChatColor.YELLOW
                                + " does not exist");
                    }
                }
                case "g=" -> {
                    String gender = arg.substring(2);
                    t.setGender(gender);
                }
                default -> c.msg(ChatColor.RED + "Unknown argument " + arg);
            }
        }
    }
}