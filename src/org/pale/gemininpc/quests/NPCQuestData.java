package org.pale.gemininpc.quests;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * How a particular NPC reacts to quest state or actions.
 *
 * - When-conditions: these are changes to the prompt in response to Facts. They can also contain actions,
 *                    which are things the LLM can opt to do in response to conversation or its own judgment.
 *
 */
class NPCQuestData {
    Set<NPCQuestCondition> conditions = new HashSet<>();

    NPCQuestData(Quest quest, List<Map<?,?>> maps){
        for(var map: maps){
            ConfigurationSection cs = new MemoryConfiguration().createSection("r",map);
            conditions.add(new NPCQuestCondition(quest, cs));
        }
    }
}
