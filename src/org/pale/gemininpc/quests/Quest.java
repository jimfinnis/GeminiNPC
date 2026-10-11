package org.pale.gemininpc.quests;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.pale.gemininpc.Plugin;
import org.pale.gemininpc.quests.triggers.Trigger;

import java.nio.file.Path;
import java.util.*;

public class Quest {
    String id;  // quest name
    String title; // quest title

    /**
     * If true, this is a per-player quest and per-player state should be kept. If false, the scope
     * is "world", and only one state is kept.
     */
    boolean player_only;

    /**
     * Other quests required before this one can happen - this is quest IDs, rather than quest references
     * to make it easier to create (we don't need to load all quests then patch this in)
     */
    Set<String> requires;

    /**
     * Set of facts - these are essentially booleans which the system can set or clear. Some facts, however,
     * spread to other sets of NPCs as "rumours"
     */
    Map<String, Fact> facts = new HashMap<>();

    /**
     * A set of deterministic (non-LLM) events that can trigger state changes
     */
    Set<Trigger> triggers = new HashSet<>();

    /**
     * map of NPC name to data - how they react to actions or state by adding things to their prompt
     * and/or setting Facts.
     */
    Map<String, NPCQuestData> npcData = new HashMap<>();


    /**
     * Load the quest
     * @param path
     */
    public Quest(Path path){
        Plugin.log("Loading quest " + path);
        YamlConfiguration root = YamlConfiguration.loadConfiguration(path.toFile());
        id = root.getString("id");
        if(id==null)
            throw new RuntimeException("No ID in quest file "+path);
        title = root.getString("title");
        if(title==null)
            throw new RuntimeException("No title in quest file "+path);
        player_only = root.getBoolean("player_only", true);

        List<String> requireslist= root.getStringList("requires"); // should default to empty list
        requires = new HashSet<String>(requireslist);

        loadFacts(root.getConfigurationSection("facts"));
        loadTriggers(root.getConfigurationSection("triggers"));
        loadNPCData(root.getConfigurationSection("participants"));
    }

    private void loadFacts(ConfigurationSection r){
        if(r==null)return;
        for(String name: r.getKeys(false)){
            Fact f = new Fact(r.getConfigurationSection(name));
            facts.put(name,f);
        }

    }

    private void loadTriggers(ConfigurationSection r){
        if(r==null)return;
        for(String name: r.getKeys(false)){
            Trigger t = new Trigger(r.getConfigurationSection(name));
            triggers.add(t);
        }

    }
    private void loadNPCData(ConfigurationSection r){
        if(r==null)return;
        for(String npc: r.getKeys(false)){
            // each NPC has a list of configuration sections, which is ugly in Bukkit. We get it as a map list here
            // and turn into a ConfigurationSection inside the ctor.
            NPCQuestData t = new NPCQuestData(this, r.getMapList(npc));
            npcData.put(npc, t);
        }

    }
}
