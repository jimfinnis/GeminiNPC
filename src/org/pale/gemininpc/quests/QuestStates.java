package org.pale.gemininpc.quests;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.pale.gemininpc.quests.expr.Value;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

/// Everyone's quest state: an `Instance` for each quest and player (or one per world quest, kept under a
/// null player). This is separate from `QuestRegistry` because the registry is rebuilt on every
/// `/gemini reload` and the state must survive that. Instances are looked up by quest ID, and brought up
/// to date if the quest has been reloaded since they were made.
///
/// The state is saved to and loaded from a YAML file:
///
///     banner:                                      # quest ID
///       3f2b...-uuid:                              # player UUID, or "world" for a world quest
///         completed: false
///         facts:
///           banner_recovered: true
///
/// State loaded for a quest which isn't loaded (its file is missing or broken) is kept as it is and saved
/// again, so that fixing the quest file doesn't find everyone's progress gone.
public class QuestStates {
    // quest ID -> state key (player UUID, or null for a world quest) -> instance
    private final Map<String, Map<UUID, Instance>> states = new HashMap<>();

    /// State loaded from the file which hasn't been matched with a loaded quest yet. It's turned into an
    /// `Instance` when the quest first asks for it, and saved as it is until then.
    private record Saved(boolean completed, Map<String, Value> facts) {}
    private final Map<String, Map<UUID, Saved>> unmatched = new HashMap<>();

    // the key used in the file for a world quest's state
    private static final String WORLD = "world";
    // Bukkit treats "." in a key as a path separator; use a character that won't be in an ID or name instead
    private static final char SEPARATOR = '\u0001';

    /// The state of a quest for a player, or null if there isn't any yet.
    /// @param player the player's UUID; ignored for world quests
    public Instance get(Quest q, UUID player){
        UUID key = q.stateKey(player);
        Map<UUID, Instance> m = states.get(q.id);
        Instance inst = m==null ? null : m.get(key);
        if(inst==null)
            return fromSaved(q, key);
        if(inst.quest!=q)
            inst.rebind(q);     // the quest has been reloaded
        return inst;
    }

    /// If there's state from the file for this quest and key, make an instance from it.
    private Instance fromSaved(Quest q, UUID key){
        Map<UUID, Saved> m = unmatched.get(q.id);
        Saved s = m==null ? null : m.remove(key);
        if(s==null)
            return null;
        Instance inst = new Instance(q, key);
        // facts the quest no longer has are dropped, as when a quest is reloaded
        for(var e: s.facts().entrySet()){
            if(inst.data.containsKey(e.getKey()))
                inst.data.put(e.getKey(), e.getValue());
        }
        inst.completed = s.completed();
        states.computeIfAbsent(q.id, k -> new HashMap<>()).put(key, inst);
        return inst;
    }

    /// The state of a quest for a player, creating it if there isn't one yet.
    public Instance getOrCreate(Quest q, UUID player){
        Instance inst = get(q, player);
        if(inst==null){
            UUID key = q.stateKey(player);
            inst = new Instance(q, key);
            states.computeIfAbsent(q.id, k -> new HashMap<>()).put(key, inst);
        }
        return inst;
    }

    /// Forget a quest's state for a player (or the world's state, for a world quest), so that it starts
    /// again from the beginning.
    public void reset(Quest q, UUID player){
        UUID key = q.stateKey(player);
        Map<UUID, Instance> m = states.get(q.id);
        if(m!=null)
            m.remove(key);
        Map<UUID, Saved> u = unmatched.get(q.id);
        if(u!=null)
            u.remove(key);
    }

    // ---- saving and loading ----

    /// Write all the state to a file. It's written to a temporary file which then replaces the old one, so
    /// a crash while saving can't leave a half-written file.
    public void save(Path file) throws IOException {
        YamlConfiguration y = new YamlConfiguration();
        y.options().pathSeparator(SEPARATOR);
        // state matched with loaded quests
        for(var q: states.entrySet()){
            for(var e: q.getValue().entrySet()){
                Instance inst = e.getValue();
                write(y, q.getKey(), e.getKey(), inst.completed, inst.data);
            }
        }
        // state for quests which aren't loaded, kept as it was
        for(var q: unmatched.entrySet()){
            for(var e: q.getValue().entrySet())
                write(y, q.getKey(), e.getKey(), e.getValue().completed(), e.getValue().facts());
        }
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        y.save(tmp.toFile());
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static void write(YamlConfiguration y, String questId, UUID key, boolean completed,
                              Map<String, Value> facts){
        ConfigurationSection q = y.isConfigurationSection(questId) ? y.getConfigurationSection(questId)
                : y.createSection(questId);
        ConfigurationSection s = q.createSection(key==null ? WORLD : key.toString());
        s.set("completed", completed);
        ConfigurationSection f = s.createSection("facts");
        for(var e: facts.entrySet())
            f.set(e.getKey(), toYaml(e.getValue()));
    }

    /// Replace all the state with what's in a file. A missing file means no state. Throws if the file
    /// can't be read or isn't valid YAML, leaving the current state alone; the caller should then avoid
    /// saving over the file, or the state in it is lost. Returns warnings about parts it had to skip.
    public List<String> load(Path file) throws IOException, InvalidConfigurationException {
        List<String> warnings = new ArrayList<>();
        Map<String, Map<UUID, Saved>> loaded = new HashMap<>();
        if(Files.exists(file)){
            YamlConfiguration y = new YamlConfiguration();
            y.options().pathSeparator(SEPARATOR);
            y.load(file.toFile());
            for(String questId: y.getKeys(false)){
                ConfigurationSection q = y.getConfigurationSection(questId);
                if(q==null){
                    warnings.add(questId + ": not a map of players to state; skipped");
                    continue;
                }
                for(String k: q.getKeys(false)){
                    ConfigurationSection s = q.getConfigurationSection(k);
                    UUID key;
                    try {
                        key = k.equals(WORLD) ? null : UUID.fromString(k);
                    } catch (IllegalArgumentException e) {
                        warnings.add(questId + ": \"" + k + "\" isn't a player UUID or \"world\"; skipped");
                        continue;
                    }
                    if(s==null){
                        warnings.add(questId + ": " + k + ": not a map of state; skipped");
                        continue;
                    }
                    Map<String, Value> facts = new LinkedHashMap<>();
                    ConfigurationSection f = s.getConfigurationSection("facts");
                    if(f!=null){
                        for(String name: f.getKeys(false)){
                            Value v = fromYaml(f.get(name));
                            if(v==null)
                                warnings.add(questId + ": " + k + ": fact " + name + " has a value of the wrong type; skipped");
                            else
                                facts.put(name, v);
                        }
                    }
                    loaded.computeIfAbsent(questId, x -> new HashMap<>())
                            .put(key, new Saved(s.getBoolean("completed"), facts));
                }
            }
        }
        // only replace the state once the whole file has been read
        states.clear();
        unmatched.clear();
        unmatched.putAll(loaded);
        return warnings;
    }

    /// Booleans are saved as true and false, other integers as numbers, and strings as strings.
    private static Object toYaml(Value v){
        if(v.equals(Value.TRUE))
            return true;
        if(v.equals(Value.FALSE))
            return false;
        return switch(v){
            case Value.Int(int n) -> n;
            case Value.Str(String s) -> s;
            default -> v.toString();
        };
    }

    private static Value fromYaml(Object o){
        return switch(o){
            case Boolean b -> b ? Value.TRUE : Value.FALSE;
            case Number n -> new Value.Int(n.intValue());
            case String s -> new Value.Str(s);
            case null, default -> null;
        };
    }
}
