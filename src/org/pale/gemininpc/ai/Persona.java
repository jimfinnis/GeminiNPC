package org.pale.gemininpc.ai;

import io.marioslab.basis.template.Template;
import io.marioslab.basis.template.TemplateContext;
import io.marioslab.basis.template.TemplateLoader;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.mcmonkey.sentinel.SentinelTrait;
import org.pale.gemininpc.GeminiNPCTrait;
import org.pale.gemininpc.Plugin;
import org.pale.gemininpc.utils.TemplateFunctions;
import org.pale.gemininpc.waypoints.Waypoints;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Represents a persona - a fictional character or user archetype.
 */
public class Persona {
    public String name;     // the name of the persona
    public String string;   // this is the base persona string before templating
    public String defaultGender = null;   // the default gender for NPCs of this persona, overriding that in the config.yml
    final public Map<String, Object> templateValues = new HashMap<>(); // template values for this persona
    final public Map<String, Object> npcMapInitialValues = new HashMap<>(); // initial values for the NPC map (accessed by "get" and "set" template funcs)

    // this is the template loader that is used for all personae. We preload it with all the common stuff,
    // and then load the persona string into it as "persona" for each persona separately.
    static TemplateLoader.MapTemplateLoader tl;

    // the "current-state" template, regenerated as part of the context in every response.
    public String current_state;

    /**
     * This method is called once at the start of the plugin to set up the template loader.
     * It loads common texts as separate templates into the template loader, so they
     * can be included.
     * @param common a map containing common texts to be loaded into the template loader
     */
    public static void initialiseTemplateLoader(Map<String, String> common) {
        tl = new TemplateLoader.MapTemplateLoader();
        for(String key : common.keySet()) {
            String value = common.get(key);
            tl.set(key, value);
        }
    }

    /**
     * Constructor for the org.pale.gemininpc.ai.Persona class.
     * @param name  the name of the file - this will become the name of the persona, with any .yml extension removed.
     * @param path  the path of the file
     */
    public Persona(String name, Path path) {
        // if the filename has a ".yml" extension, it's a yaml file otherwise it's plain text.
        var plugin = Plugin.getInstance();
        plugin.getLogger().info("Loading "+path);

        if (path.toString().endsWith(".yml")) {
            loadYamlConfig(name, path);
        } else {
            this.string = plugin.readFile(path, "I was unable to load my persona, but I'm not afraid. Tell anyone who will listen.");
            this.name = name;
            this.defaultGender = null; // we're not a YAML config so there's no default gender here. Just setting here to make this clear.
        }
    }

    private void loadYamlConfig(String name, Path path) {
        var plugin = Plugin.getInstance();
        // remove the ".yml" extension from the name - that will be the name of the persona.
        this.name = name.substring(0, name.length() - 4);
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(path.toFile());

        // the most important thing is the string itself.
        this.string = yaml.getString("string");

        // then pick up important templates variables.

        // this overrides the setting in the config.yml, and in turn can be overriden in the NPC.
        // if null, use the default in the config.yml
        this.defaultGender = yaml.getString("default-gender", plugin.defaultGender);

        // and any other variables we want to set. These will override those given in the main config.
        ConfigurationSection c = yaml.getConfigurationSection("template-values");
        if (c != null) {
            for (String key : c.getKeys(false)) {
                templateValues.put(key, c.get(key));
            }
        }

        // there may be more template variables in some extra files specified in my own config
        // The root of relative file paths here is assumed to be the plugin's data folder
        List<String> lst = yaml.getStringList("yaml-files");
        for(String filename: lst){  // above line will give empty list if data not found
            Path p = Paths.get(filename);
            if(!p.isAbsolute()){
                p = Paths.get(plugin.getDataFolder().getAbsolutePath(),filename);
            }
            YamlConfiguration yaml2 = YamlConfiguration.loadConfiguration(p.toFile());
            plugin.getLogger().severe("Loaded "+p.toAbsolutePath());
            for (String key : yaml2.getKeys(false)) {
                templateValues.put(key, yaml2.get(key));
            }
        }

        for(String k: templateValues.keySet()){
            plugin.getLogger().severe("Key: "+k+" value: "+templateValues.get(k));
        }

        // store initial NPC map data
        c = yaml.getConfigurationSection("npc-map");
        if (c != null) {
            for (String key : c.getKeys(false)) {
                npcMapInitialValues.put(key, c.get(key));
            }
        }

        // we *may* also have a string called "current-state" which is a template used every update.
        // It contains information about what the character is currently thinking.

        this.current_state = yaml.getString("current-state");
        plugin.getLogger().severe("Current state template: "+current_state);
    }

    /**
     * Used by both the system prompt persona template and current state template to
     * create a template context with the custom template functions and some extra things.
     * Doesn't add stuff that's going to be constant for the NPC, such as waypoints.
     *
     * @param t the trait
     * @return a new template context
     */
    private TemplateContext generateTemplateContext(GeminiNPCTrait t){
        // the doc advises creating a new context each time!
        var plugin = Plugin.getInstance();
        TemplateContext tc = new TemplateContext();
        // add the template variables given in the main config
        for (String key : plugin.templateValues.keySet()) {
            tc.set(key, plugin.templateValues.get(key));
        }

        // add our own values (which may override those given in the main config)
        for (String key : templateValues.keySet()) {
            plugin.getLogger().warning("Template value "+key+"="+templateValues.get(key));
            tc.set(key, templateValues.get(key));
        }

        // add the functions object
        t.getTemplateFunctions().addFunctions(tc);

        // set some special values
        tc.set("name",t.getNPC().getName());
        tc.set("gender",t.gender);
        tc.set("isSentinel",t.isSentinel());
        tc.set("isShop", t.isShop());

        return tc;
    }

    private String generateString(TemplateContext tc, String template_string){
        // we want to run the template engine on this string, so load it via the template loader
        // and render it
        var logger = Plugin.getInstance().getLogger();
        tl.set("temp", template_string);
        logger.severe("template string: "+template_string);
        Template pt = tl.load("temp");

        String processed = pt.render(tc);
        // now maybe replace newlines, because we need the persona to be JSONable (probably).
        if(Plugin.getInstance().removeNewlinesFromPersona)
            processed = processed.replaceAll("\\n"," ");
        return processed;
    }

    /**
     * Apply the template system to the persona but with the PRNG keyed
     * to the NPC's name, so it will always be the same if there are random
     * elements. We don't want weird personality changes!
     * @param t the trait
     * @return the processed persona string
     */
    public String generateSystemInstructions(GeminiNPCTrait t) {

        TemplateContext tc = generateTemplateContext(t);

        var logger = Plugin.getInstance().getLogger();
//        logger.info("Generating system instructions for "+t.getNPC().getName());
/*
        for(String s: tc.getVariables()){
            Plugin.log("Template variable: "+s+" = "+tc.get(s));
        }
*/
        // set waypoints
        if(t.waypoints.getNumberOfWaypoints()>0) {
            Map<String, String> waymap = new HashMap<>();
            for (String name : t.waypoints.getWaypointNames()) {
                try {
                    waymap.put(name, t.waypoints.getWaypoint(name).desc);
                } catch (Waypoints.Exception e) {
                    // really shouldn't happen; just ignore
                }
            }
            tc.set("waypoints", waymap);
        }
        tc.set("hasWaypoints", t.waypoints.getNumberOfWaypoints()>0);

        // get the actual persona string
        String persona = generateString(tc, string);
        // then set it back into the template context as "persona"
        tc.set("persona", persona);

        logger.info("Persona for "+t.getNPC().getName()+" from template "+t.personaName+": "+persona);

        // load and render the common template, which will include the persona
        Template template = tl.load("common"); // ffs - clunky that we have to do the set/load like this
        String s= template.render(tc);
        s = s.replaceAll("\\n\\n+", "\n"); // replace multiple newlines
        return s;
//        logger.info("Instructions: "+s);
    }

    public String getCurrentState(GeminiNPCTrait t){

        Plugin.getInstance().getLogger().severe("Generating current state for: "+t.getNPC().getName());
        if(current_state == null)
            return null;
        TemplateContext tc = generateTemplateContext(t);
        String current = generateString(tc, current_state).trim();
        Plugin.getInstance().getLogger().info("Current state: "+current);
        return current;
    }

}
