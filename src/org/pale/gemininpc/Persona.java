package org.pale.gemininpc;

import io.marioslab.basis.template.Template;
import io.marioslab.basis.template.TemplateContext;
import io.marioslab.basis.template.TemplateLoader;
import org.bukkit.configuration.file.YamlConfiguration;
import org.mcmonkey.sentinel.SentinelTrait;
import org.pale.gemininpc.utils.TemplateFunctions;

import java.nio.file.Path;

/**
 * Represents a persona - a fictional character or user archetype.
 */
public class Persona {
    public String name;     // the name of the persona
    public String string;   // this is the base persona string before templating

    /**
     * Constructor for the org.pale.gemininpc.Persona class.
     * @param name  the name of the file - this will become the name of the persona, with any .yml extension removed.
     * @param path  the path of the file
     */
    Persona (String name, Path path) {
        // if the filename has a ".yml" extension, it's a yaml file otherwise it's plain text.
        var plugin = Plugin.getInstance();
        if (path.toString().endsWith(".yml")) {
            // remove the ".yml" extension from the name
            this.name = name.substring(0, name.length() - 4);
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(path.toFile());
            this.string = yaml.getString("string");
        } else {
            this.string = plugin.readFile(path, "I was unable to load my persona, but I'm not afraid. Tell anyone who will listen.");
            this.name = name;
        }
    }

    /**
     * Apply the template system to the persona but with the PRNG keyed
     * to the NPC's name, so it will always be the same if there are random
     * elements. We don't want weird personality changes!
     * @param t the trait
     * @return the processed persona string
     */
    public String generateString(GeminiNPCTrait t) {
        var plugin = Plugin.getInstance();
        // the doc advises creating a new context each time!
        TemplateContext tc = new TemplateContext();
        for (String key : plugin.templateValues.keySet()) {
            tc.set(key, plugin.templateValues.get(key));
        }

        // create a template function object for this NPC
        TemplateFunctions f = new TemplateFunctions(t);
        f.addFunctions(tc);

        // set some special values
        tc.set("name",t.getNPC().getName());
        tc.set("gender",t.gender);
        tc.set("isSentinel",t.getNPC().hasTrait(SentinelTrait.class));

        for(String s: tc.getVariables()){
            Plugin.log("Template variable: "+s+" = "+tc.get(s));
        }

        // this seems cumbersome - we just want to run the templating engine on
        // the data. Note that we're prepending the common text first, so the template
        // engine can run on that!
        TemplateLoader.MapTemplateLoader tl = new TemplateLoader.MapTemplateLoader();
        tl.set("data", plugin.common+"\n"+string);
        Template template = tl.load("data"); // ffs
        String s= template.render(tc);
        Plugin.log("Template applied to " + t.getNPC().getName() + " is " + s);
        return s;
    }

}
