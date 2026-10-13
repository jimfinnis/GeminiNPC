package org.pale.gemininpc.quests;

import org.pale.gemininpc.quests.expr.Evaluator;
import org.pale.gemininpc.quests.expr.TreeNode;
import org.pale.gemininpc.quests.expr.Value;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/// The state of a quest for a particular player, or for the world: the values of its facts, and whether
/// it's complete. Instances are kept in `QuestStates`, which outlives the `Quest` objects, so that a
/// `/gemini reload` doesn't lose anyone's progress.
public class Instance {
    Quest quest;
    final UUID playerId;  // the player's UUID, or null if a world quest
    // the data is stored as Value objects for extensibility
    final Map<String, Value> data = new HashMap<>();
    boolean completed;

    Instance(Quest q, UUID playerId){
        quest = q;
        this.playerId = playerId;

        // initialise the data by getting initial values from each Fact
        for(var e: q.facts.entrySet()){
            data.put(e.getKey(), e.getValue().createInstance());
        }
    }

    /// Bring the instance up to date with a reloaded version of its quest, whose facts may have changed.
    /// Facts which still exist keep their values, new facts get their initial values, and facts which no
    /// longer exist are dropped.
    void rebind(Quest q){
        quest = q;
        data.keySet().retainAll(q.facts.keySet());
        for(var e: q.facts.entrySet())
            data.putIfAbsent(e.getKey(), e.getValue().createInstance());
    }

    public Value get(String n){
        Value d = data.get(n);
        if(d==null)
            throw new IllegalArgumentException("trying to get value of "+n+" in instance of quest "+quest.id);
        return d;
    }

    public void set(String n, Value v){
        if(!data.containsKey(n)) // will have been set with a default if it's valid
            throw new IllegalArgumentException("trying to set value of "+n+" in instance of quest "+quest.id);
        data.put(n,v);
    }

    public boolean isCompleted(){
        return completed;
    }

    /// Evaluate an expression from the quest (such as a `when:` or `complete-when`) against the current
    /// facts, and say whether it's true, i.e. a nonzero integer. The expressions were checked against the
    /// quest's facts when it loaded, so every identifier has a value here.
    public boolean test(TreeNode expr){
        Evaluator ev = new Evaluator();
        for(var e: data.entrySet())
            ev.set(e.getKey(), e.getValue());
        return ev.evaluate(expr) instanceof Value.Int(int i) && i!=0;
    }
}
