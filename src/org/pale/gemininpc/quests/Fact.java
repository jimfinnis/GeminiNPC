package org.pale.gemininpc.quests;

import org.bukkit.configuration.ConfigurationSection;

import java.util.HashSet;
import java.util.Set;

/**
 * A fact as part of a quest - typically something that has happened that we can check.
 * It's just a boolean at the moment, but the fact may spread to other users as "rumour", in
 * which case other NPCs know it via a special string. There can be several of these sets.
 *
 * Note that this doesn't hold the fact's actual value - that's part of the quest state.
 */
class Fact {
    record Rumour (
            Set<String> npcNames, // NPCs to whom this particular rumour spreads
            String text         // the text that gets adding to those NPC prompts when the rumour is true
    ) {}

    Set<Rumour> rumours = new HashSet<>();

    /**
     * @param cs the fact's section, or null if the fact has no data (e.g. "mustard-found:" with no value)
     */
    Fact(ConfigurationSection cs){
        if(cs==null)return;
        // rumours is a map of rumour name to rumour data
        ConfigurationSection rs = cs.getConfigurationSection("rumours");
        if(rs==null)return;
        for(String name: rs.getKeys(false)){
            ConfigurationSection r = rs.getConfigurationSection(name);
            if(r==null)
                throw new RuntimeException("Rumour "+name+" in fact "+cs.getName()+" is not a section");
            String text = r.getString("text");
            if(text==null)
                throw new RuntimeException("No text in rumour "+name+" in fact "+cs.getName());
            rumours.add(new Rumour(new HashSet<>(r.getStringList("spreads-to")), text));
        }
    }
}
