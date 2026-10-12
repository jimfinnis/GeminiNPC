package org.pale.gemininpc.quests.triggers;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.pale.gemininpc.quests.Instance;
import org.pale.gemininpc.quests.Quest;
import org.pale.gemininpc.quests.QuestException;
import org.pale.gemininpc.utils.TextUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

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

    /// @param where where the trigger is in the quest file, for warnings
    public Trigger(Quest q, String where, ConfigurationSection cs) {
        quest = q;

        // this is very annoying - "on" is considered a boolean in YAML, so if I want "on" as a key I have to call
        // it "true." I could use another name, but "on" is easy to remember. If someone quotes it ("on":) it
        // stays as "on", so accept that too.
        q.warnUnknownKeys(cs, where, Set.of("on", "true", "set"));
        String cstr = cs.getString("on", cs.getString("true"));
        if (cstr == null)
            throw new QuestException("no on: condition");

        cond = parseCondition(cstr);

        // actions - you can only have one of each type, simply because it makes the quest code neater.
        String astr = cs.getString("set");
        if (astr != null) {
            // at the moment the set is just the name of a boolean fact. Later we should be able to
            // set multiple data and possibly to non-boolean values. We could make use of the expression
            // system to do this, with assignment operators (and with access to assignment operators gated
            // on the type of expression we are parsing).
            if(quest.getFact(astr)==null)
                throw new QuestException("set: there is no fact called \"" + astr + "\"");
            actions.add(new SetAction(astr));
        }
        // add more later!

        if(actions.isEmpty())
            throw new QuestException("no action (e.g. set:), so this trigger would do nothing");
    }

    private ITriggerCondition parseCondition(String s){
        List<String> ss = TextUtils.splitQuoted(s);
        if(ss.isEmpty())
            throw new QuestException("on: condition is empty");
        switch(ss.getFirst()){
            case "give" -> {
                if(ss.size()!=4 || !ss.get(2).equals("to"))
                    throw new QuestException("on: bad give condition \"" + s + "\", should be: give ITEM to NPCNAME");
                return new GiveCondition(ss.get(1), ss.get(3));
            }
            case "read-book" -> {
                if(ss.size()!=2)
                    throw new QuestException("on: bad read-book condition \"" + s
                            + "\", should be: read-book NAME_OR_TITLE");
                return new ReadBookCondition(ss.get(1));
            }
            default -> throw new QuestException("on: unknown condition type \"" + ss.getFirst()
                    + "\" in \"" + s + "\", should be give or read-book");
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
