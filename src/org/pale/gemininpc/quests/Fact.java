package org.pale.gemininpc.quests;

import org.bukkit.configuration.ConfigurationSection;
import org.pale.gemininpc.quests.expr.Value;

import java.util.HashSet;
import java.util.Set;

/**
 * A fact as part of a quest - typically something that has happened that we can check.
 * It's just a boolean at the moment, but the fact may spread to other users as "rumour", in
 * which case other NPCs know it via a special string. There can be several of these sets.
 *
 * Note that this doesn't hold the fact's actual value - that's part of the quest state.
 */
public class Fact {
    record Rumour (
            Set<String> npcNames, // NPCs to whom this particular rumour spreads
            String text         // the text that gets adding to those NPC prompts when the rumour is true
    ) {}

    Set<Rumour> rumours = new HashSet<>();

    /// @param where where the fact is in the quest file, for warnings
    /// @param cs the fact's section, or null if the fact has no data (e.g. "mustard-found:" with no value)
    Fact(Quest q, String where, ConfigurationSection cs){
        if(cs==null)return;
        q.warnUnknownKeys(cs, where, Set.of("rumours"));
        // rumours is a map of rumour name to rumour data
        if(!cs.contains("rumours"))return;
        ConfigurationSection rs = cs.getConfigurationSection("rumours");
        if(rs==null)
            throw new QuestException("rumours must be a map of rumour names to rumours");
        for(String name: rs.getKeys(false)){
            ConfigurationSection r = rs.getConfigurationSection(name);
            if(r==null)
                throw new QuestException("rumours." + name + " must contain spreads-to: and text:");
            q.warnUnknownKeys(r, where + ".rumours." + name, Set.of("spreads-to", "text"));
            String text = r.getString("text");
            if(text==null)
                throw new QuestException("rumours." + name + ": no text:");
            rumours.add(new Rumour(new HashSet<>(r.getStringList("spreads-to")), text));
        }
    }

    /**
     * Create the initial value for this fact. At the moment this just assumes booleans which are false.
     * We *may* expand this later.
     */
    Value createInstance(){
        return Value.FALSE;
    }
}
