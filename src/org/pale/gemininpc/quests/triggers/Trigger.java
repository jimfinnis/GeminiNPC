package org.pale.gemininpc.quests.triggers;

import org.bukkit.configuration.ConfigurationSection;
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

    public Trigger(ConfigurationSection cs) {
        if(cs==null)
            throw new RuntimeException("Trigger section is empty");

        String cstr = cs.getString("on");
        if (cstr == null)
            throw new RuntimeException("No condition for trigger");
        cond = parseCondition(cstr);

        // actions - you can only have one of each type, simply because it makes the quest code neater.
        String astr = cs.getString("set");
        if (astr != null)
            actions.add(new SetAction(astr));
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
     * The event has been detected and has occurred.
     */
    boolean check(TriggerEvent e) {
        if(cond.check(e)) {
            for(ITriggerAction a: actions)
                a.run();
            return true;
        }
        return false;
    }
}
