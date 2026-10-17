package org.pale.gemininpc.quests;

import org.pale.gemininpc.quests.expr.Value;

import java.util.*;

/// What the `/gemini quest` debugging commands do, separated from the command itself (which finds the
/// player and prints the result) so that it can be tested without a server. Each method returns the lines
/// to show, and throws a `QuestException` with a message for the user if its arguments are wrong.
public class QuestDebug {
    private final QuestRegistry reg;

    public QuestDebug(QuestRegistry reg){
        this.reg = reg;
    }

    private Quest quest(String id){
        Quest q = reg.get(id);
        if(q==null)
            throw new QuestException("there is no quest \"" + id + "\"");
        return q;
    }

    /// One line per loaded quest: its ID, title, scope and what it requires.
    public List<String> list(){
        List<String> lines = new ArrayList<>();
        if(reg.size()==0)
            lines.add("No quests are loaded.");
        for(Quest q: reg.all()){
            lines.add(q.id + ": " + q.title + " (" + (q.player_only ? "player" : "world") + " quest"
                    + (q.requires.isEmpty() ? "" : ", requires " + String.join(", ", q.requires)) + ")");
        }
        return lines;
    }

    /// One line per group of NPCs, listing its members (see `QuestNPCs`).
    public static List<String> groups(Collection<QuestNPCs.NPCInfo> npcs){
        List<String> lines = new ArrayList<>();
        var groups = QuestNPCs.groups(npcs);
        if(groups.isEmpty())
            lines.add("No NPCs are in groups.");
        for(var e: groups.entrySet())
            lines.add(e.getKey() + ": " + String.join(", ", e.getValue().stream().map(QuestNPCs.NPCInfo::name).toList()));
        return lines;
    }

    /// Each quest's status for a player, with the facts of any quest they've made progress in.
    /// @param player the player's UUID; world quests show the world's state whoever is asked about
    public List<String> state(UUID player){
        List<String> lines = new ArrayList<>();
        if(reg.size()==0)
            lines.add("No quests are loaded.");
        for(Quest q: reg.all()){
            Instance inst = reg.getStates().get(q, player);
            String status;
            if(reg.isComplete(q, player))
                status = "complete";
            else if(reg.isActive(q, player))
                status = inst==null ? "active, no progress yet" : "active";
            else
                status = "not active yet; waiting for " + String.join(", ", incompleteRequirements(q, player));
            lines.add(q.id + (q.player_only ? "" : " (world)") + ": " + status);
            if(inst!=null)
                lines.add("  " + describeFacts(q, inst));
        }
        return lines;
    }

    private List<String> incompleteRequirements(Quest q, UUID player){
        List<String> waiting = new ArrayList<>();
        for(String r: q.requires){
            Quest rq = reg.get(r);
            if(rq==null)
                waiting.add(r + " (which doesn't exist)");
            else if(!reg.isComplete(rq, player))
                waiting.add(r);
        }
        return waiting;
    }

    /// The facts in file order, e.g. "banner_recovered=true, prune_consulted=false".
    private static String describeFacts(Quest q, Instance inst){
        if(q.facts.isEmpty())
            return "(no facts)";
        List<String> parts = new ArrayList<>();
        for(String f: q.facts.keySet())
            parts.add(f + "=" + describe(inst.get(f)));
        return String.join(", ", parts);
    }

    private static String describe(Value v){
        if(v.equals(Value.TRUE))
            return "true";
        if(v.equals(Value.FALSE))
            return "false";
        return v.toString();
    }

    /// Set a fact for a player, then check whether that completes the quest. This works whether or not
    /// the quest is active, so that you can set up a situation to test. Completion can't be undone by
    /// setting a fact back to false; use `reset` for that.
    public List<String> set(UUID player, String questId, String fact, String value){
        Quest q = quest(questId);
        if(!q.facts.containsKey(fact))
            throw new QuestException("quest " + q.id + " has no fact \"" + fact + "\"; its facts are "
                    + String.join(", ", q.facts.keySet()));
        Value v = switch(value.toLowerCase(Locale.ROOT)){
            case "true", "1", "yes", "on" -> Value.TRUE;
            case "false", "0", "no", "off" -> Value.FALSE;
            default -> throw new QuestException("the value should be true or false, not \"" + value + "\"");
        };
        Instance inst = reg.getStates().getOrCreate(q, player);
        inst.set(fact, v);
        List<String> lines = new ArrayList<>();
        lines.add(q.id + (q.player_only ? "" : " (world)") + ": set " + fact + "=" + describe(v));
        if(!reg.isActive(q, player) && !inst.completed)
            lines.add("Note: the quest isn't active yet, so its triggers and prompts won't apply.");
        if(reg.checkCompletion(q, inst))
            lines.add(q.id + " is now complete.");
        lines.add("  " + describeFacts(q, inst));
        return lines;
    }

    /// Forget a player's progress in a quest, so it starts again. Naming a world quest resets it for everyone.
    /// If `questId` is null, all the player's own (player-scope) quests are reset; world quests are left
    /// alone, since resetting them affects everyone and should be done deliberately, by name.
    public List<String> reset(UUID player, String questId){
        List<String> lines = new ArrayList<>();
        if(questId!=null){
            Quest q = quest(questId);
            reg.getStates().reset(q, player);
            lines.add("Reset " + q.id + (q.player_only ? "" : " for everyone, since it's a world quest"));
            return lines;
        }
        for(Quest q: reg.all()){
            if(q.player_only){
                reg.getStates().reset(q, player);
                lines.add("Reset " + q.id);
            }
        }
        if(lines.isEmpty())
            lines.add("There are no player quests to reset; world quests must be reset by name.");
        return lines;
    }
}
