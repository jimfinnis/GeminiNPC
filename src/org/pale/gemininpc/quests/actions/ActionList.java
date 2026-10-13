package org.pale.gemininpc.quests.actions;

import org.bukkit.configuration.ConfigurationSection;
import org.pale.gemininpc.quests.Quest;
import org.pale.gemininpc.quests.QuestException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/// A list of actions which run in order, as written in a quest file:
///
///     do:
///       - set: banner_recovered
///       - set: [helga_exposed, prune_consulted]
///
/// Each item is a map with one key, the type of action, whose value is the action's argument. This is
/// used by triggers' `do:`, and is meant for `on-complete` and anything else that runs actions.
public class ActionList {
    private final List<IQuestAction> actions = new ArrayList<>();

    /// Load the list in `cs` under `key`. A missing key gives an empty list; whether that's allowed is up
    /// to the caller. Errors say which item is wrong, e.g. "do #2: set: there is no fact called "x"".
    public ActionList(Quest q, ConfigurationSection cs, String key){
        // Each action is a map with a single key (the action type) rather than a string like the
        // "give X to Y" of a trigger's on: condition. That way the argument is ordinary YAML: it can be
        // a list (set: [a, b]) or a long piece of text without any extra quoting, and YAML parses it
        // for us. A string would need splitting ourselves, and runs into YAML's quoting rules when it
        // starts with a quote or contains ": ".
        List<Map<?,?>> items = Quest.mapList(cs, key, key);
        for(int i=0; i<items.size(); i++){
            Map<?,?> item = items.get(i);
            String where = key + " #" + (i+1);
            // a missing "-" between two actions makes them one item with two keys
            if(item.size()!=1)
                throw new QuestException(where + " should be one action, but has " + item.keySet()
                        + "; does each action start with \"- \"?");
            var entry = item.entrySet().iterator().next();
            QuestException.in(where, () -> actions.add(parseAction(q, entry.getKey().toString(), entry.getValue())));
        }
    }

    private static IQuestAction parseAction(Quest q, String type, Object arg){
        return switch(type){
            case "set" -> new SetAction(q.factNames(type, arg));
            // add more action types here
            default -> throw new QuestException("unknown action \"" + type + "\", should be set");
        };
    }

    public boolean isEmpty(){
        return actions.isEmpty();
    }

    public int size(){
        return actions.size();
    }

    /// Run all the actions in order.
    public void run(ActionContext ctx){
        for(IQuestAction a: actions)
            a.run(ctx);
    }
}
