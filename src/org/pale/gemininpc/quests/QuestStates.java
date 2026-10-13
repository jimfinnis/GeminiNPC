package org.pale.gemininpc.quests;

import java.util.*;

/// Everyone's quest state: an `Instance` for each quest and player (or one per world quest, kept under a
/// null player). This is separate from `QuestRegistry` because the registry is rebuilt on every
/// `/gemini reload` and the state must survive that. Instances are looked up by quest ID, and brought up
/// to date if the quest has been reloaded since they were made.
///
/// Later, this is what will be saved and loaded so that progress survives a restart.
public class QuestStates {
    // quest ID -> state key (player UUID, or null for a world quest) -> instance
    private final Map<String, Map<UUID, Instance>> states = new HashMap<>();

    /// The state of a quest for a player, or null if there isn't any yet.
    /// @param player the player's UUID; ignored for world quests
    public Instance get(Quest q, UUID player){
        Map<UUID, Instance> m = states.get(q.id);
        if(m==null)
            return null;
        Instance inst = m.get(q.stateKey(player));
        if(inst!=null && inst.quest!=q)
            inst.rebind(q);     // the quest has been reloaded
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
        Map<UUID, Instance> m = states.get(q.id);
        if(m!=null)
            m.remove(q.stateKey(player));
    }
}
