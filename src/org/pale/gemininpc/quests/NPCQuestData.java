package org.pale.gemininpc.quests;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * How a particular NPC reacts to quest state or actions.
 *
 * - When-conditions: these are changes to the prompt in response to Facts. They can also contain actions,
 *                    which are things the LLM can opt to do in response to conversation or its own judgment.
 *                    They are ordered, and if more than one condition applies the prompts are given to the NPC in
 *                    the order they appear in the file
 *
 */
class NPCQuestData {
    List<NPCQuestCondition> conditions = new ArrayList<>();

    /// @param where where this NPC's data is in the quest file, e.g. "participants.helga"
    NPCQuestData(Quest quest, String where, List<Map<?,?>> maps){
        for(int i=0; i<maps.size(); i++){
            String itemWhere = where + " #" + (i+1);
            ConfigurationSection cs = new MemoryConfiguration().createSection("r", maps.get(i));
            QuestException.in(itemWhere, () -> conditions.add(new NPCQuestCondition(quest, itemWhere, cs)));
        }
    }
}
