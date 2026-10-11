package org.pale.gemininpc.quests;

import org.pale.gemininpc.quests.expr.Value;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

///  An instance of a quest for a particular player, or for the world.
public class Instance {
    Quest quest;
    UUID playerId;  // the player's UUID, or null if a world quest
    // the data is stored as Value objects for extensibility
    Map<String, Value> data = new HashMap<>();

    Instance(Quest q, UUID playerId){
        quest = q;
        this.playerId = playerId;

        // initialise the data by getting initial values from each Fact
        for(var e: q.facts.entrySet()){
            data.put(e.getKey(), e.getValue().createInstance());
        }
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
}
