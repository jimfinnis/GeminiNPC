package org.pale.gemininpc.quests;

import org.bukkit.ChatColor;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

/// How quests find the NPC they mean by a name such as `helga` in `participants:` or `give X to helga`.
///
/// A name refers to the NPC with that name, ignoring case and colour codes. If no NPC has that name, it can
/// instead be the name of a persona, as long as exactly one NPC uses that persona. That lets a quest say
/// `prune` for the NPC called "Prunesquallor" whose persona is `prune`, but stops it silently picking one of
/// several NPCs that share a persona.
///
/// Names are resolved each time they're needed, not cached, so they follow NPCs being renamed or given new
/// personae.
public class QuestNPCs {
    /// What quests need to know about an NPC.
    /// @param id the Citizens NPC ID
    /// @param name the NPC's name
    /// @param persona the NPC's persona name, or null if it doesn't have the GeminiNPC trait
    public record NPCInfo(int id, String name, String persona) {}

    /// Where the list of NPCs comes from. `Plugin` sets this to read Citizens' NPC registry; tests can set
    /// their own.
    public interface Directory {
        Collection<NPCInfo> all();
    }

    private static Directory directory = List::of;

    public static void setDirectory(Directory d) {
        directory = d;
    }

    public static Directory getDirectory() {
        return directory;
    }

    /// The result of resolving a name: either the NPC, or why it couldn't be found.
    public record Resolution(NPCInfo npc, String problem) {}

    /// Put an NPC name, persona name, or a name used in a quest, into the form used to compare them: no
    /// colour codes, and lower case.
    public static String normalise(String name) {
        return ChatColor.stripColor(name).toLowerCase(Locale.ROOT);
    }

    /// Find the NPC a quest means by `questName`, from the given NPCs.
    public static Resolution resolve(String questName, Collection<NPCInfo> npcs) {
        String n = normalise(questName);
        List<NPCInfo> byName = npcs.stream().filter(i -> normalise(i.name()).equals(n)).toList();
        if(byName.size()==1)
            return new Resolution(byName.getFirst(), null);
        if(byName.size()>1)
            return new Resolution(null, "there are " + byName.size() + " NPCs called \"" + questName
                    + "\", so it isn't clear which is meant");
        List<NPCInfo> byPersona = npcs.stream()
                .filter(i -> i.persona()!=null && normalise(i.persona()).equals(n)).toList();
        if(byPersona.size()==1)
            return new Resolution(byPersona.getFirst(), null);
        if(byPersona.size()>1)
            return new Resolution(null, "there is no NPC called \"" + questName + "\", and "
                    + byPersona.size() + " NPCs use that persona, so it isn't clear which is meant");
        return new Resolution(null, "there is no NPC called \"" + questName + "\", or with that persona");
    }

    /// Resolve a name against the current NPCs.
    public static Resolution resolve(String questName) {
        return resolve(questName, directory.all());
    }

    /// True if the NPC with the given ID is the one a quest means by `questName`.
    public static boolean matches(int npcId, String questName) {
        NPCInfo npc = resolve(questName).npc();
        return npc!=null && npc.id()==npcId;
    }
}
