package org.pale.gemininpc.quests;

import java.util.*;

/// All the loaded quests, by ID, with the checks which need more than one quest file (such as `requires`)
/// or need to know which NPCs exist. It doesn't use the plugin, so it can be tested; `Plugin` builds a new
/// one each time the config is loaded.
public class QuestRegistry {
    private final Map<String, Quest> quests = new LinkedHashMap<>();
    // the file each quest came from, by quest ID, for messages
    private final Map<String, String> files = new HashMap<>();

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
