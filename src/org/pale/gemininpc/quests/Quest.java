package org.pale.gemininpc.quests;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.pale.gemininpc.Plugin;
import org.pale.gemininpc.quests.triggers.Trigger;

import java.nio.file.Path;
import java.util.*;

public class Quest {
    public String id;  // quest name
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
     * Instances of the quest actually running, keyed by player UUID. If there is only one global instance
     * it is keyed under null.
     */
    Map<UUID, Instance> instances = new HashMap<>();


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
        String scope = root.getString("scope");
        if(scope==null)
            throw new RuntimeException("No scope in quest file "+path);
        if(scope.equals("player"))
            player_only = true;
        else if(scope.equals("world"))
            player_only = false;
        else
            throw new RuntimeException("Bad scope \""+scope+"\" in quest file "+path+", should be player or world");

        List<String> requireslist= root.getStringList("requires"); // should default to empty list
        requires = new HashSet<String>(requireslist);   // Ignored at the moment TODO

        loadFacts(root.getConfigurationSection("facts"));
        loadTriggers(root.getMapList("triggers"));
        loadNPCData(root.getConfigurationSection("participants"));
    }

    private void loadFacts(ConfigurationSection r){
        if(r==null)return;
        for(String name: r.getKeys(false)){
            Fact f = new Fact(r.getConfigurationSection(name));
            facts.put(name,f);
        }

    }

    // triggers are a list of maps.
    private void loadTriggers(List<Map<?,?>> maps){
        if(maps==null)return;
        for(var map: maps){
            ConfigurationSection cs = new MemoryConfiguration().createSection("r",map);
            Trigger t = new Trigger(this, cs);
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

    ///  Start the quest for a given player. If this is a world quest, we create it with a null
    /// player key instead of that passed in!
    Instance start(Player p){
        UUID key = instanceKey(p);
        if(instances.containsKey(key))
            throw new RuntimeException(String.format("Cannot have more than one instance of quest %s for %s ",
                    id,(key==null) ? "the world" : ("player "+p.getName())
                    ));
        var instance = new Instance(this, key);
        instances.put(key, instance);
        return instance;
    }

    /// Return an instance of the quest, or the instance keyed to null for world quests.
    public Instance getInstance(Player p){
        return instances.get(instanceKey(p));
    }

    /// The key for a player's instance: their UUID, which (unlike the `Player` object) stays the same
    /// when they log out and back in. World quests have one instance, keyed under null.
    private UUID instanceKey(Player p){
        return player_only ? p.getUniqueId() : null;
    }

    public Fact getFact(String s){
        return facts.get(s);
    }
}
