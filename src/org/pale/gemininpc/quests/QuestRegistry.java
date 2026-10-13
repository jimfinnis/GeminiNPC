package org.pale.gemininpc.quests;

import org.bukkit.entity.Player;
import org.pale.gemininpc.quests.triggers.TriggerEvent;

import java.util.*;

/// All the loaded quests, by ID, with the checks which need more than one quest file (such as `requires`)
/// or need to know which NPCs exist, and the rules for which quests are active for a player. It doesn't
/// use the plugin, so it can be tested; `Plugin` builds a new one each time the config is loaded, passing
/// in the same `QuestStates` each time so that progress survives a reload.
///
/// A quest is active for a player once every quest it requires is complete for them, and stays active
/// until its own `complete-when` is true. So a quest with no `requires` is active from the start. There's
/// no separate step to start a quest.
public class QuestRegistry {
    private final Map<String, Quest> quests = new LinkedHashMap<>();
    // the file each quest came from, by quest ID, for messages
    private final Map<String, String> files = new HashMap<>();
    private final QuestStates states;

    public QuestRegistry(QuestStates states){
        this.states = states;
    }

    public QuestStates getStates(){
        return states;
    }

    /// Add a quest which has just been loaded. Throws a `QuestException` if another quest has the same ID,
    /// in which case the first one is kept.
    public void add(Quest q, String fileName){
        String other = files.get(q.id);
        if(other!=null)
            throw new QuestException("quest id \"" + q.id + "\" is already used by " + other);
        quests.put(q.id, q);
        files.put(q.id, fileName);
    }

    public Quest get(String id){
        return quests.get(id);
    }

    public Collection<Quest> all(){
        return Collections.unmodifiableCollection(quests.values());
    }

    public int size(){
        return quests.size();
    }

    // ---- quest state ----

    /// True if the quest is complete for the player (or for the world, if it's a world quest).
    public boolean isComplete(Quest q, UUID player){
        Instance inst = states.get(q, player);
        return inst!=null && inst.completed;
    }

    /// True if the quest is active for the player: everything it requires is complete for them, and it
    /// isn't complete itself. A required quest which doesn't exist is never complete (and gives a warning
    /// when the quests load), so a quest requiring it is never active.
    public boolean isActive(Quest q, UUID player){
        if(isComplete(q, player))
            return false;
        for(String r: q.requires){
            Quest rq = quests.get(r);
            if(rq==null || !isComplete(rq, player))
                return false;
        }
        return true;
    }

    /// The player's state for a quest if the quest is active for them, creating it the first time; null if
    /// the quest isn't active.
    public Instance activeInstance(Quest q, UUID player){
        return isActive(q, player) ? states.getOrCreate(q, player) : null;
    }

    /// The quests active for a player, in the order they were loaded.
    public List<Quest> activeQuests(UUID player){
        return quests.values().stream().filter(q -> isActive(q, player)).toList();
    }

    /// Check whether a quest has just been completed, after its facts have changed, and mark it complete if
    /// so. Returns true if it has. Quests which require it become active as a result.
    /// Later, this is where `on-complete` will run.
    public boolean checkCompletion(Quest q, Instance inst){
        if(inst.completed || q.completeWhen==null || !inst.test(q.completeWhen))
            return false;
        inst.completed = true;
        return true;
    }

    /// Run an event through the triggers of every quest active for the player, running the actions of any
    /// that match, then check those quests for completion. Returns what happened, for the log, e.g.
    /// `["banner triggers #1", "banner completed"]`; empty if no trigger fired.
    ///
    /// The active quests are found before any triggers run, so a quest which only becomes active because
    /// this event completed another one doesn't also see the event.
    public List<String> fire(Player p, TriggerEvent e){
        UUID player = p.getUniqueId();
        Map<Quest, Instance> active = new LinkedHashMap<>();
        for(Quest q: quests.values()){
            Instance inst = activeInstance(q, player);
            if(inst!=null)
                active.put(q, inst);
        }
        List<String> fired = new ArrayList<>();
        for(var entry: active.entrySet()){
            Quest q = entry.getKey();
            Instance inst = entry.getValue();
            boolean changed = false;
            for(int i=0; i<q.triggers.size(); i++){
                if(q.triggers.get(i).check(inst, p, e)){
                    fired.add(q.id + " triggers #" + (i+1));
                    changed = true;
                }
            }
            if(changed && checkCompletion(q, inst))
                fired.add(q.id + " completed");
        }
        return fired;
    }

    /// Check that every quest's `requires` names a quest which exists. Call this once all the quests are
    /// loaded. Returns warnings, each starting with the quest file's name.
    public List<String> checkRequires(){
        List<String> warnings = new ArrayList<>();
        for(Quest q: quests.values()){
            for(String r: q.requires){
                if(r.equals(q.id))
                    warnings.add(files.get(q.id) + ": requires: the quest requires itself, so it can never start");
                else if(!quests.containsKey(r))
                    warnings.add(files.get(q.id) + ": requires: there is no quest \"" + r
                            + "\", so this quest can never start");
            }
        }
        return warnings;
    }

    /// Check that the NPC names used in quests each find exactly one NPC (see `QuestNPCs.resolve`). Returns
    /// warnings, each starting with the quest file's name.
    public List<String> checkNPCs(Collection<QuestNPCs.NPCInfo> npcs){
        List<String> warnings = new ArrayList<>();
        for(Quest q: quests.values()){
            String file = files.get(q.id);
            // participants are given prompts, so they need the trait, and each must be a different NPC
            Map<Integer, String> participantIds = new HashMap<>();
            for(String npc: q.npcData.keySet()){
                String where = file + ": participants." + npc + ": ";
                QuestNPCs.Resolution r = QuestNPCs.resolve(npc, npcs);
                if(r.npc()==null)
                    warnings.add(where + r.problem());
                else if(r.npc().persona()==null)
                    warnings.add(where + "NPC \"" + r.npc().name()
                            + "\" doesn't have the GeminiNPC trait, so it can't be given quest prompts");
                else {
                    String other = participantIds.put(r.npc().id(), npc);
                    if(other!=null)
                        warnings.add(where + "this is the same NPC (\"" + r.npc().name() + "\") as participants."
                                + other + "; put them together");
                }
            }
            // other references (e.g. who an item is given to) can be any NPC
            for(int i=0; i<q.triggers.size(); i++){
                for(String npc: q.triggers.get(i).npcNames()){
                    QuestNPCs.Resolution r = QuestNPCs.resolve(npc, npcs);
                    if(r.npc()==null)
                        warnings.add(file + ": triggers #" + (i+1) + ": " + r.problem());
                }
            }
        }
        return warnings;
    }
}
