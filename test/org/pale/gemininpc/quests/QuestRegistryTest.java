package org.pale.gemininpc.quests;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/// The checks which need several quests, or the list of NPCs: duplicate IDs, `requires` and NPC names.
public class QuestRegistryTest {
    @TempDir
    Path dir;

    final List<String> warnings = new ArrayList<>();
    final QuestRegistry reg = new QuestRegistry();

    /// Load a quest from YAML and add it to the registry under the given file name.
    private Quest add(String fileName, String yaml) throws IOException {
        Path p = dir.resolve(fileName);
        Files.writeString(p, yaml);
        Quest q = new Quest(p, warnings);
        reg.add(q, fileName);
        return q;
    }

    private static String quest(String id, String rest) {
        return "id: " + id + "\ntitle: T\nscope: player\n" + rest;
    }

    // ---- duplicate IDs ----

    @Test
    void duplicateIdRejected() throws IOException {
        Quest first = add("a.yml", quest("q", ""));
        QuestException e = assertThrows(QuestException.class, () -> add("b.yml", quest("q", "")));
        assertEquals("quest id \"q\" is already used by a.yml", e.getMessage());
        assertSame(first, reg.get("q"));   // the first one is kept
        assertEquals(1, reg.size());
    }

    // ---- requires ----

    @Test
    void requiresKnownQuest() throws IOException {
        add("a.yml", quest("a", ""));
        add("b.yml", quest("b", "requires: [a]\n"));
        assertEquals(List.of(), reg.checkRequires());
    }

    @Test
    void requiresUnknownQuest() throws IOException {
        add("b.yml", quest("b", "requires: [a]\n"));
        assertEquals(List.of("b.yml: requires: there is no quest \"a\", so this quest can never start"),
                reg.checkRequires());
    }

    @Test
    void requiresItself() throws IOException {
        add("b.yml", quest("b", "requires: [b]\n"));
        assertEquals(List.of("b.yml: requires: the quest requires itself, so it can never start"),
                reg.checkRequires());
    }

    // ---- NPC names ----

    // an NPC with the GeminiNPC trait (and so a persona), and one without
    static QuestNPCs.NPCInfo chat(int id, String name, String persona) {
        return new QuestNPCs.NPCInfo(id, name, persona);
    }

    static QuestNPCs.NPCInfo plain(int id, String name) {
        return new QuestNPCs.NPCInfo(id, name, null);
    }

    static final String PARTICIPANT = """
            facts:
              f: {}
            participants:
              helga:
                - when: f
                  prompt: hello
            """;

    @Test
    void participantByName() throws IOException {
        add("a.yml", quest("a", PARTICIPANT));
        assertEquals(List.of(), reg.checkNPCs(List.of(chat(1, "Helga", "nun"), plain(2, "Bob"))));
    }

    @Test
    void participantByPersona() throws IOException {
        add("a.yml", quest("a", PARTICIPANT));
        assertEquals(List.of(), reg.checkNPCs(List.of(chat(1, "Helga the Nun", "helga"))));
    }

    @Test
    void participantMissing() throws IOException {
        add("a.yml", quest("a", PARTICIPANT));
        assertEquals(List.of("a.yml: participants.helga: there is no NPC called \"helga\", or with that persona"),
                reg.checkNPCs(List.of(plain(2, "Bob"))));
    }

    @Test
    void participantPersonaShared() throws IOException {
        add("a.yml", quest("a", PARTICIPANT));
        String w = reg.checkNPCs(List.of(chat(1, "Helga One", "helga"), chat(2, "Helga Two", "helga")))
                .getFirst();
        assertTrue(w.startsWith("a.yml: participants.helga: there is no NPC called \"helga\", and 2 NPCs use"), w);
    }

    @Test
    void participantWithoutTrait() throws IOException {
        add("a.yml", quest("a", PARTICIPANT));
        assertEquals(List.of("a.yml: participants.helga: NPC \"Helga\" doesn't have the GeminiNPC trait, "
                + "so it can't be given quest prompts"), reg.checkNPCs(List.of(plain(1, "Helga"))));
    }

    @Test
    void twoParticipantsSameNPC() throws IOException {
        // "helga" is the persona of the NPC called "Sister Helga"
        add("a.yml", quest("a", """
                facts:
                  f: {}
                participants:
                  helga:
                    - when: f
                      prompt: hello
                  sister helga:
                    - when: f
                      prompt: goodbye
                """));
        assertEquals(List.of("a.yml: participants.sister helga: this is the same NPC (\"Sister Helga\") as "
                + "participants.helga; put them together"), reg.checkNPCs(List.of(chat(1, "Sister Helga", "helga"))));
    }

    @Test
    void giveToMissingNPC() throws IOException {
        add("a.yml", quest("a", """
                facts:
                  f: {}
                triggers:
                  - on: read-book x
                    do:
                      - set: f
                  - on: give paper to bob
                    do:
                      - set: f
                """));
        assertEquals(List.of("a.yml: triggers #2: there is no NPC called \"bob\", or with that persona"),
                reg.checkNPCs(List.of(chat(1, "Helga", "helga"))));
    }

    @Test
    void giveToNPCWithoutTraitIsFine() throws IOException {
        // you can give items to any NPC, not just ones with the trait
        add("a.yml", quest("a", """
                facts:
                  f: {}
                triggers:
                  - on: give paper to bob
                    do:
                      - set: f
                """));
        assertEquals(List.of(), reg.checkNPCs(List.of(plain(1, "Bob"))));
    }

    @Test
    void exampleQuestNPCs() {
        // as on the test server: some NPCs are named in the quest by their persona
        Quest q = new Quest(Paths.get("exampleconfig/quests/banner.yml"), warnings);
        reg.add(q, "banner.yml");
        assertEquals(List.of(), reg.checkNPCs(List.of(
                chat(1, "Lady Chanter", "ladychanter"),
                chat(2, "Prunesquallor", "prune"),
                chat(3, "Helga", "helga"),
                chat(4, "Lofgar", "lofgar"))));
    }
}
