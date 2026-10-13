package org.pale.gemininpc.quests;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.pale.gemininpc.quests.expr.Parser;
import org.pale.gemininpc.quests.expr.TreeNode;
import org.pale.gemininpc.quests.triggers.Trigger;

import java.io.IOException;
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
    Map<String, Fact> facts = new LinkedHashMap<>();   // in the order they are in the file

    /**
     * Deterministic (non-LLM) events that can trigger state changes, in the order they're in the file
     */
    List<Trigger> triggers = new ArrayList<>();

    /**
     * map of NPC name to data - how they react to actions or state by adding things to their prompt
     * and/or setting Facts.
     */
    Map<String, NPCQuestData> npcData = new HashMap<>();

    /// Something for the player to do, shown to them so they can track the quest. It's done when the
    /// expression is true.
    record Objective(String text, TreeNode done) {}

    List<Objective> objectives = new ArrayList<>();

    /// The quest is complete when this is true; null if the quest has no complete-when and is open-ended.
    TreeNode completeWhen;

    /// Every key allowed at the top level of a quest file. Anything else gets a warning, since it's
    /// probably a typo.
    private static final Set<String> TOP_LEVEL_KEYS = Set.of("id", "title", "scope", "requires", "facts",
            "triggers", "participants", "objectives", "complete-when", "on-complete");

    /// Problems which don't stop the quest loading, each as "where: message". This is the list passed to
    /// the constructor, so the caller still has the warnings if loading then fails.
    final List<String> warnings;

    /// Load the quest. Throws a `QuestException` if the file is bad; the message says where in the file
    /// the problem is but not which file, which the caller adds. Warnings are added to `warnings` rather
    /// than logged, so that quests can be loaded without the plugin running (e.g. in tests).
    public Quest(Path path, List<String> warnings){
        this.warnings = warnings;
        // loadConfiguration() would log a syntax error and carry on with an empty config, so load
        // the file ourselves to get the error (with its line number) as an exception.
        YamlConfiguration root = new YamlConfiguration();
        try {
            root.load(path.toFile());
        } catch (IOException | InvalidConfigurationException e) {
            throw new QuestException("cannot read file: " + e.getMessage());
        }
        warnUnknownKeys(root, "top level", TOP_LEVEL_KEYS);

        id = requireString(root, "id");
        title = requireString(root, "title");
        String scope = requireString(root, "scope");
        player_only = switch(scope){
            case "player" -> true;
            case "world" -> false;
            default -> throw new QuestException("scope must be player or world, not \"" + scope + "\"");
        };

        if(root.contains("requires") && !root.isList("requires"))
            throw new QuestException("requires must be a list of quest IDs, e.g. requires: [quest1, quest2]");
        requires = new HashSet<>(root.getStringList("requires"));   // Ignored at the moment TODO

        // facts must come first, because everything else is checked against them
        loadFacts(section(root, "facts"));
        loadTriggers(mapList(root, "triggers", "triggers"));
        loadNPCData(section(root, "participants"));
        loadObjectives(mapList(root, "objectives", "objectives"));

        // a quest with no complete-when is allowed; it's open-ended, e.g. a world quest that never finishes
        if(root.contains("complete-when"))
            completeWhen = parseExpression("complete-when", root.getString("complete-when"));
    }

    /// Parse an expression whose identifiers must all be facts of this quest.
    /// @param key the key the expression came from, for the error message
    TreeNode parseExpression(String key, String s){
        Parser parser = new Parser();
        parser.registerVariables(facts.keySet());
        try {
            return parser.parse(s);
        } catch (RuntimeException e) {
            throw new QuestException(key + ": " + e.getMessage() + ", in \"" + s + "\"");
        }
    }

    /// Get the facts named by a key such as `set:`, which may be a single fact or a list of them, and check
    /// that they all exist. Returns an empty list if the key isn't there.
    public List<String> factNames(ConfigurationSection cs, String key){
        if(!cs.contains(key))
            return List.of();
        return factNames(key, cs.get(key));
    }

    /// Get the facts named by a value straight from the YAML, which may be a single fact or a list of them,
    /// and check that they all exist.
    /// @param key the key the value came from, for error messages
    public List<String> factNames(String key, Object value){
        List<String> names = switch(value){
            case null -> List.of();
            case List<?> l -> l.stream().map(String::valueOf).toList();
            case Map<?,?> m -> throw new QuestException(key + ": should be a fact or a list of facts, not a map");
            case ConfigurationSection s -> throw new QuestException(key + ": should be a fact or a list of facts, not a map");
            default -> List.of(value.toString());
        };
        if(names.isEmpty())
            throw new QuestException(key + ": no facts given");
        for(String n: names){
            if(!facts.containsKey(n))
                throw new QuestException(key + ": there is no fact called \"" + n + "\"");
        }
        return names;
    }

    private void loadFacts(ConfigurationSection r){
        if(r==null)return;
        for(String name: r.getKeys(false)){
            // a fact's value is either a section of data, or a placeholder like "x" if it has none
            String where = "facts." + name;
            QuestException.in(where, () -> facts.put(name, new Fact(this, where, r.getConfigurationSection(name))));
        }
    }

    // triggers are a list of maps.
    private void loadTriggers(List<Map<?,?>> maps){
        for(int i=0; i<maps.size(); i++){
            String where = "triggers #" + (i+1);
            ConfigurationSection cs = new MemoryConfiguration().createSection("r", maps.get(i));
            QuestException.in(where, () -> triggers.add(new Trigger(this, where, cs)));
        }
    }

    private void loadNPCData(ConfigurationSection r){
        if(r==null)return;
        for(String npc: r.getKeys(false)){
            // each NPC has a list of configuration sections, which is ugly in Bukkit. We get it as a map list here
            // and turn into a ConfigurationSection inside the ctor.
            String where = "participants." + npc;
            npcData.put(npc, new NPCQuestData(this, where, mapList(r, npc, where)));
        }
    }

    private void loadObjectives(List<Map<?,?>> maps){
        for(int i=0; i<maps.size(); i++){
            String where = "objectives #" + (i+1);
            ConfigurationSection cs = new MemoryConfiguration().createSection("r", maps.get(i));
            QuestException.in(where, () -> {
                warnUnknownKeys(cs, where, Set.of("text", "done"));
                String text = cs.getString("text");
                if(text==null)
                    throw new QuestException("no text:");
                String done = cs.getString("done");
                if(done==null)
                    throw new QuestException("no done:");
                objectives.add(new Objective(text, parseExpression("done", done)));
            });
        }
    }

    /// Get a string which must be present.
    private static String requireString(ConfigurationSection cs, String key){
        String s = cs.getString(key);
        if(s==null)
            throw new QuestException("no " + key + ":");
        return s;
    }

    /// Get a section which may be missing (giving null) but must be a map if it's there. Bukkit's
    /// getConfigurationSection() would quietly return null for a list or a string.
    private static ConfigurationSection section(ConfigurationSection cs, String key){
        if(!cs.contains(key))
            return null;
        if(!cs.isConfigurationSection(key))
            throw new QuestException(key + " must be a map of names to values");
        return cs.getConfigurationSection(key);
    }

    /// Get a list of maps which may be missing (giving an empty list). Bukkit's getMapList() quietly drops
    /// anything that isn't a map, and gives an empty list if the value isn't a list at all, so a mistake
    /// like `- give x` instead of `- on: give x` would just vanish.
    public static List<Map<?,?>> mapList(ConfigurationSection cs, String key, String where){
        if(!cs.contains(key))
            return List.of();
        if(!cs.isList(key))
            throw new QuestException(where + " must be a list, with each item starting with \"- \"");
        List<?> list = cs.getList(key);
        List<Map<?,?>> maps = new ArrayList<>();
        for(int i=0; i<list.size(); i++){
            if(!(list.get(i) instanceof Map<?,?> m))
                throw new QuestException(where + " #" + (i+1) + " must be a map of keys to values, not \""
                        + list.get(i) + "\"");
            maps.add(m);
        }
        return maps;
    }

    /// Record a problem with the quest that doesn't stop it loading.
    public void warn(String where, String msg){
        warnings.add(where + ": " + msg);
    }

    /// Warn about any keys in a section which aren't in `allowed`. Unknown keys are usually typos, or
    /// features that aren't written yet, and would otherwise be silently ignored.
    public void warnUnknownKeys(ConfigurationSection cs, String where, Set<String> allowed){
        for(String k: cs.getKeys(false)){
            if(!allowed.contains(k))
                warn(where, "unknown key \"" + k + "\" (ignored)");
        }
    }

    /// The key a player's state for this quest is kept under (see `QuestStates`): their UUID, which
    /// (unlike the `Player` object) stays the same when they log out and back in. A world quest has one
    /// state shared by everyone, kept under null.
    UUID stateKey(UUID player){
        return player_only ? player : null;
    }

    public Fact getFact(String s){
        return facts.get(s);
    }
}
