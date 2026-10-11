package org.pale.gemininpc.quests.triggers;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.pale.gemininpc.quests.Instance;
import org.pale.gemininpc.quests.Quest;
import org.pale.gemininpc.utils.TextUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * triggers are events that are triggered by external actions that aren't detected by the LLM - they
 * happen directly. A trigger has two parts - a TriggerCondition and an TriggerAction.
 *
 * These two are separate but combined here - the idea is that we have several condition types and
 * several action types, and we can combine them.
 */
public class Trigger {
    ITriggerCondition cond;
    List<ITriggerAction> actions = new ArrayList<>();
    Quest quest;

    public Trigger(Quest q, ConfigurationSection cs) {
        if(cs==null)
            throw new RuntimeException("Trigger section is empty");

        quest = q;

        // this is very annoying - "on" is considered a boolean in YAML, so if I want "on" as a key I have to call
        // it "true." I could use another name, but "on" is easy to remember. If someone quotes it ("on":) it
        // stays as "on", so accept that too.
        String cstr = cs.getString("on", cs.getString("true"));
        if (cstr == null)
            throw new RuntimeException("No condition (on:) for trigger in quest "+q.id);

        cond = parseCondition(cstr);

        // actions - you can only have one of each type, simply because it makes the quest code neater.
        String astr = cs.getString("set");
        if (astr != null) {
            // at the moment the set is just the name of a boolean fact. Later we should be able to
            // set multiple data and possibly to non-boolean values. We could make use of the expression
            // system to do this, with assignment operators (and with access to assignment operators gated
            // on the type of expression we are parsing).
            if(quest.getFact(astr)==null)
                throw new RuntimeException("Fact "+astr+" does not exist in quest "+q.id);
            actions.add(new SetAction(astr));
        }
        // add more later!
    }

    private ITriggerCondition parseCondition(String s){
        List<String> ss = TextUtils.splitQuoted(s);
        if(ss.isEmpty())
            throw new RuntimeException("condition is empty");
        switch(ss.getFirst()){
            case "give" -> {
                if(ss.size()!=4 || !ss.get(2).equals("to"))
                    throw new RuntimeException("Bad give condition, should be: give ITEM to NPCNAME: "+s);
                return new GiveCondition(ss.get(1), ss.get(3));
            }
            default -> throw new RuntimeException("Unknown condition type: "+ss.getFirst());
        }
    }

    /**
     * Check if an event is valid for this player (i.e. the player is doing this
     * trigger's quest), and then run it if the check matches.
     *
     * Also check the world event.
     */
    boolean check(Player p, TriggerEvent e) {
        Instance inst = quest.getInstance(p);
        if(inst==null)
            return false;   // that player/world isn't doing that quest
        if(cond.check(e)) {
            for(ITriggerAction a: actions)
                a.run(inst);
            return true;
        }
        return false;
    }
}
