package org.pale.gemininpc.quests;

import org.bukkit.ChatColor;

import java.util.*;

/// How quests find the NPCs they mean by a name such as `helga` in `participants:` or `give X to helga`.
///
/// A name refers to a group of NPCs if any NPC is in a group of that name (see below). Otherwise it refers
/// to the NPC with that name, ignoring case and colour codes. If no NPC has that name, it can instead be the
/// name of a persona, as long as exactly one NPC uses that persona. That lets a quest say `prune` for the
/// NPC called "Prunesquallor" whose persona is `prune`, but stops it silently picking one of several NPCs
/// that share a persona.
///
/// An NPC is in a group if its persona lists the group under `groups:`, or if the group's entry under
/// `npc-groups:` in config.yml names the NPC or its persona (see `groupsFor`).
///
/// Names are resolved each time they're needed, not cached, so they follow NPCs being renamed or given new
/// personae.
public class QuestNPCs {
    /// What quests need to know about an NPC.
    /// @param id the Citizens NPC ID
    /// @param name the NPC's name
    /// @param persona the NPC's persona name, or null if it doesn't have the GeminiNPC trait
    /// @param groups the groups the NPC is in, normalised (see `normalise`)
    public record NPCInfo(int id, String name, String persona, Set<String> groups) {
        /// An NPC in no groups.
        public NPCInfo(int id, String name, String persona) {
            this(id, name, persona, Set.of());
        }
    }

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

    /// The result of resolving a name: the NPCs it means, or why it couldn't be resolved.
    /// @param npcs the members of the group, or the single NPC; empty if there's a problem
    /// @param isGroup true if the name is a group's
    /// @param problem why no NPC was found, or null
    public record Resolution(List<NPCInfo> npcs, boolean isGroup, String problem) {
        static Resolution of(NPCInfo npc) {
            return new Resolution(List.of(npc), false, null);
        }

        static Resolution failed(String problem) {
            return new Resolution(List.of(), false, problem);
        }

        /// The NPC, if the name means a single NPC rather than a group; otherwise null.
        public NPCInfo npc() {
            return isGroup || npcs.isEmpty() ? null : npcs.getFirst();
        }
    }

    /// Put an NPC name, persona name, group name, or a name used in a quest, into the form used to compare
    /// them: no colour codes, and lower case.
    public static String normalise(String name) {
        return ChatColor.stripColor(name).toLowerCase(Locale.ROOT);
    }

    /// The groups an NPC is in, normalised.
    /// @param npcName the NPC's name
    /// @param persona its persona name, or null if it doesn't have the GeminiNPC trait
    /// @param personaGroups the groups its persona lists
    /// @param configGroups the `npc-groups:` map from config.yml: group name to the names of NPCs or personae
    public static Set<String> groupsFor(String npcName, String persona, Collection<String> personaGroups,
                                        Map<String, List<String>> configGroups) {
        Set<String> groups = new TreeSet<>();
        for(String g: personaGroups)
            groups.add(normalise(g));
        String n = normalise(npcName);
        String p = persona==null ? null : normalise(persona);
        for(var e: configGroups.entrySet()){
            for(String member: e.getValue()){
                String m = normalise(member);
                if(m.equals(n) || m.equals(p))
                    groups.add(normalise(e.getKey()));
            }
        }
        return groups;
    }

    /// Check the `npc-groups:` map from config.yml against the NPCs: each member listed must be the name or
    /// persona of at least one NPC. Returns a warning for each that isn't.
    public static List<String> checkConfigGroups(Map<String, List<String>> configGroups,
                                                 Collection<NPCInfo> npcs) {
        List<String> warnings = new ArrayList<>();
        for(var e: configGroups.entrySet()){
            for(String member: e.getValue()){
                if(!namesAnNPC(member, npcs))
                    warnings.add("npc-groups." + e.getKey() + ": there is no NPC called \"" + member
                            + "\", or with that persona");
            }
        }
        return warnings;
    }

    /// True if some NPC has this name or persona, regardless of groups. Used to warn about a group with the
    /// same name as an NPC, which hides the NPC.
    public static boolean namesAnNPC(String name, Collection<NPCInfo> npcs) {
        String n = normalise(name);
        return npcs.stream().anyMatch(i -> normalise(i.name()).equals(n)
                || (i.persona()!=null && normalise(i.persona()).equals(n)));
    }

    /// The members of every group, by group name, sorted by group name.
    public static Map<String, List<NPCInfo>> groups(Collection<NPCInfo> npcs) {
        Map<String, List<NPCInfo>> groups = new TreeMap<>();
        for(NPCInfo i: npcs){
            for(String g: i.groups())
                groups.computeIfAbsent(g, k -> new ArrayList<>()).add(i);
        }
        return groups;
    }

    /// The names of a group's members, without colour codes and sorted, so that a persona choosing from
    /// them with its seeded random numbers chooses the same each time. Empty if there's no such group. Used
    /// by the `group()` template function.
    public static List<String> memberNames(String group, Collection<NPCInfo> npcs) {
        String g = normalise(group);
        return npcs.stream().filter(i -> i.groups().contains(g))
                .map(i -> ChatColor.stripColor(i.name())).sorted().toList();
    }

    /// Find the NPCs a quest means by `questName`, from the given NPCs.
    public static Resolution resolve(String questName, Collection<NPCInfo> npcs) {
        String n = normalise(questName);
        List<NPCInfo> members = npcs.stream().filter(i -> i.groups().contains(n)).toList();
        if(!members.isEmpty())
            return new Resolution(members, true, null);
        List<NPCInfo> byName = npcs.stream().filter(i -> normalise(i.name()).equals(n)).toList();
        if(byName.size()==1)
            return Resolution.of(byName.getFirst());
        if(byName.size()>1)
            return Resolution.failed("there are " + byName.size() + " NPCs called \"" + questName
                    + "\", so it isn't clear which is meant");
        List<NPCInfo> byPersona = npcs.stream()
                .filter(i -> i.persona()!=null && normalise(i.persona()).equals(n)).toList();
        if(byPersona.size()==1)
            return Resolution.of(byPersona.getFirst());
        if(byPersona.size()>1)
            return Resolution.failed("there is no NPC called \"" + questName + "\", and "
                    + byPersona.size() + " NPCs use that persona, so it isn't clear which is meant");
        return Resolution.failed("there is no NPC or group called \"" + questName + "\", or NPC with that persona");
    }

    /// Resolve a name against the current NPCs.
    public static Resolution resolve(String questName) {
        return resolve(questName, directory.all());
    }

    /// True if the NPC with the given ID is the one a quest means by `questName`, or is in the group it
    /// means.
    public static boolean matches(int npcId, String questName) {
        return resolve(questName).npcs().stream().anyMatch(i -> i.id()==npcId);
    }
}
