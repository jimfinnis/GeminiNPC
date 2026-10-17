package org.pale.gemininpc.quests;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/// Groups of NPCs: who's in them (from personae and config), resolving a group's name, and the checks.
public class QuestGroupsTest {
    @TempDir
    Path dir;

    static final Map<String, List<String>> CONFIG = Map.of(
            "doctors", List.of("prune"),                        // by persona
            "Castle Staff", List.of("§6Prunesquallor", "angren")); // by name, with colour and case

    static QuestNPCs.NPCInfo npc(int id, String name, String persona, String... personaGroups) {
        return new QuestNPCs.NPCInfo(id, name, persona,
                QuestNPCs.groupsFor(name, persona, List.of(personaGroups), CONFIG));
    }

    static final List<QuestNPCs.NPCInfo> NPCS = List.of(
            npc(1, "Prunesquallor", "prune"),
            npc(2, "Lady Chanter", "ladychanter", "nuns"),
            npc(3, "Helga", "helga", "Nuns"),
            npc(4, "Angren", "lofgar"),
            npc(5, "Bob", null));

    @AfterEach
    void resetDirectory() {
        QuestNPCs.setDirectory(List::of);
    }

    private static List<Integer> ids(String name) {
        return QuestNPCs.resolve(name, NPCS).npcs().stream().map(QuestNPCs.NPCInfo::id).toList();
    }

    @Test
    void membershipFromPersonaAndConfig() {
        assertEquals(Set.of("doctors", "castle staff"), NPCS.get(0).groups());
        assertEquals(Set.of("nuns"), NPCS.get(2).groups());
        assertEquals(Set.of("castle staff"), NPCS.get(3).groups());
        assertEquals(Set.of(), NPCS.get(4).groups());
    }

    @Test
    void groupResolvesToItsMembers() {
        QuestNPCs.Resolution r = QuestNPCs.resolve("NUNS", NPCS);
        assertTrue(r.isGroup());
        assertNull(r.npc());
        assertNull(r.problem());
        assertEquals(List.of(2, 3), ids("nuns"));
        assertEquals(List.of(1, 4), ids("castle staff"));
    }

    @Test
    void singleNPCStillResolves() {
        QuestNPCs.Resolution r = QuestNPCs.resolve("helga", NPCS);
        assertFalse(r.isGroup());
        assertEquals(3, r.npc().id());
    }

    @Test
    void matchesAMember() {
        QuestNPCs.setDirectory(() -> NPCS);
        assertTrue(QuestNPCs.matches(3, "nuns"));
        assertFalse(QuestNPCs.matches(1, "nuns"));
    }

    @Test
    void listOfGroups() {
        assertEquals(List.of("castle staff: Prunesquallor, Angren", "doctors: Prunesquallor",
                "nuns: Lady Chanter, Helga"), QuestDebug.groups(NPCS));
    }

    @Test
    void memberNamesForTemplates() {
        var npcs = List.of(new QuestNPCs.NPCInfo(1, "§6Ylva", "nun", Set.of("nuns")),
                new QuestNPCs.NPCInfo(2, "Frieda", "nun", Set.of("nuns")),
                new QuestNPCs.NPCInfo(3, "Bob", null, Set.of()));
        // sorted, without colour codes, and the group name is matched ignoring case
        assertEquals(List.of("Frieda", "Ylva"), QuestNPCs.memberNames("Nuns", npcs));
        assertEquals(List.of(), QuestNPCs.memberNames("monks", npcs));
    }

    @Test
    void configMemberMustExist() {
        assertEquals(List.of(), QuestNPCs.checkConfigGroups(CONFIG, NPCS));
        assertEquals(List.of("npc-groups.cooks: there is no NPC called \"swelter\", or with that persona"),
                QuestNPCs.checkConfigGroups(Map.of("cooks", List.of("swelter")), NPCS));
    }

    // ---- groups in quests ----

    final QuestRegistry reg = new QuestRegistry(new QuestStates());

    private void add(String yaml) throws IOException {
        Path p = dir.resolve("a.yml");
        Files.writeString(p, "id: a\ntitle: T\nscope: player\n" + yaml);
        reg.add(new Quest(p, new ArrayList<>()), "a.yml");
    }

    @Test
    void groupAsParticipantGivesEachMemberItsPrompts() throws IOException {
        QuestNPCs.setDirectory(() -> NPCS);
        add("""
                facts:
                  f: {}
                participants:
                  helga:
                    - when: not f
                      prompt: helga's own
                  nuns:
                    - when: not f
                      prompt: for all nuns
                """);
        // overlapping a single NPC with a group isn't a mistake
        assertEquals(List.of(), reg.checkNPCs(NPCS));
        UUID alice = UUID.randomUUID();
        assertEquals(List.of("helga's own", "for all nuns"), reg.promptsFor(3, alice));
        assertEquals(List.of("for all nuns"), reg.promptsFor(2, alice));
        assertEquals(List.of(), reg.promptsFor(1, alice));
    }

    @Test
    void groupParticipantMemberWithoutTrait() throws IOException {
        add("""
                facts:
                  f: {}
                participants:
                  staff:
                    - when: f
                      prompt: hello
                """);
        var npcs = List.of(new QuestNPCs.NPCInfo(1, "Bob", null, Set.of("staff")),
                new QuestNPCs.NPCInfo(2, "Helga", "helga", Set.of("staff")));
        assertEquals(List.of("a.yml: participants.staff: NPC \"Bob\", in the group, doesn't have the GeminiNPC "
                + "trait, so it can't be given quest prompts"), reg.checkNPCs(npcs));
    }

    @Test
    void spreadsToIsChecked() throws IOException {
        add("""
                facts:
                  f:
                    rumours:
                      r:
                        spreads-to: [nuns, monks]
                        except: [abbot]
                        text: whisper
                """);
        assertEquals(List.of("a.yml: facts.f: spreads-to: there is no NPC or group called \"monks\", or NPC "
                + "with that persona", "a.yml: facts.f: except: there is no NPC or group called \"abbot\", or "
                + "NPC with that persona"), reg.checkNPCs(NPCS));
    }

    @Test
    void groupHidingAnNPCWarns() throws IOException {
        add("""
                facts:
                  f: {}
                triggers:
                  - on: give paper to helga
                    do:
                      - set: f
                """);
        var npcs = List.of(new QuestNPCs.NPCInfo(1, "Helga", "helga", Set.of()),
                new QuestNPCs.NPCInfo(2, "Frieda", "nun", Set.of("helga")));
        assertEquals(List.of("a.yml: triggers #1: \"helga\" is a group, and also the name or persona of an NPC; "
                + "it means the group"), reg.checkNPCs(npcs));
    }

    @Test
    void exampleQuestSpreadsToNuns() {
        reg.add(new Quest(Path.of("exampleconfig/quests/banner.yml"), new ArrayList<>()), "banner.yml");
        List<QuestNPCs.NPCInfo> server = List.of(
                npc(1, "Lady Chanter", "ladychanter", "nuns"),
                npc(2, "Prunesquallor", "prune"),
                npc(3, "Helga", "helga", "nuns"),
                npc(4, "Angren", "lofgar"));
        assertEquals(List.of(), reg.checkNPCs(server));
    }
}
