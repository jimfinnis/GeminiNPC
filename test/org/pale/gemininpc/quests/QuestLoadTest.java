package org.pale.gemininpc.quests;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/// Loading quest files, and the errors and warnings for bad ones. Most tests write a small quest to a
/// temporary file; `banner.yml` next to this file is a copy of the example quest on the test server.
public class QuestLoadTest {
    @TempDir
    Path dir;

    final List<String> warnings = new ArrayList<>();

    // the minimum a quest needs, to put in front of the part being tested
    static final String HEADER = """
            id: q
            title: A Quest
            scope: player
            """;

    private Quest load(String yaml) throws IOException {
        Path p = dir.resolve("q.yml");
        Files.writeString(p, yaml);
        return new Quest(p, warnings);
    }

    /// Load a quest which should fail, and return the error message. The error must be one the loader
    /// raised deliberately, not something unexpected like a NullPointerException.
    private String fails(String yaml) {
        QuestException e = assertThrows(QuestException.class, () -> load(yaml));
        assertTrue(e.isExpected(), "unexpected error: " + e);
        return e.getMessage();
    }

    // ---- the example quest and other valid quests ----

    @Test
    void exampleQuestLoads() {
        Quest q = new Quest(Paths.get("test/org/pale/gemininpc/quests/banner.yml"), warnings);
        assertEquals(List.of(), warnings);
        assertEquals("banner", q.id);
        assertEquals("The Ominous Banner", q.title);
        assertTrue(q.player_only);
        assertEquals(Set.of("banner_recovered", "prune_consulted", "helga_exposed", "helga_confessed",
                "knows_lofgar", "lofgar_revealed"), q.facts.keySet());
        assertEquals(2, q.triggers.size());
        assertEquals(Set.of("ladychanter", "helga", "prune", "lofgar"), q.npcData.keySet());
        assertEquals(3, q.npcData.get("ladychanter").conditions.size());

        Set<Fact.Rumour> rumours = q.getFact("helga_exposed").rumours;
        assertEquals(1, rumours.size());
        assertEquals(Set.of("nuns"), rumours.iterator().next().npcNames());

        // conditions are in a set, so find Helga's one with an action
        NPCQuestCondition.Action confess = q.npcData.get("helga").conditions.stream()
                .map(c -> c.actions.get("confess")).filter(a -> a != null).findFirst().orElseThrow();
        assertEquals(List.of("helga_confessed", "helga_exposed"), confess.set());
        assertEquals("You admit to the player that you hung the banner", confess.desc());
        NPCQuestCondition.Action reveal = q.npcData.get("prune").conditions.stream()
                .map(c -> c.actions.get("reveal_angren")).filter(a -> a != null).findFirst().orElseThrow();
        assertEquals(List.of("lofgar_revealed"), reveal.set());   // a single fact rather than a list
        assertEquals("You tell the player that Angren the tomb-keeper is Lofgar", reveal.desc());

        assertEquals(3, q.objectives.size());
        assertEquals("Retrieve the banner from the convent walls", q.objectives.getFirst().text());
        assertNotNull(q.completeWhen);
    }

    @Test
    void minimalQuestLoads() throws IOException {
        Quest q = load(HEADER);
        assertEquals("q", q.id);
        assertTrue(q.facts.isEmpty());
        assertTrue(q.objectives.isEmpty());
        assertNull(q.completeWhen);
        assertEquals(List.of(), warnings);
    }

    @Test
    void worldScope() throws IOException {
        Quest q = load("""
                id: q
                title: A Quest
                scope: world
                """);
        assertFalse(q.player_only);
    }

    // ---- the top level ----

    @Test
    void yamlSyntaxErrorGivesLine() {
        String msg = fails(HEADER + "facts:\n  a: {\n");
        assertTrue(msg.startsWith("cannot read file:"), msg);
        assertTrue(msg.contains("line"), msg);
    }

    @Test
    void missingId() {
        assertEquals("no id:", fails("title: T\nscope: player\n"));
    }

    @Test
    void badScope() {
        assertEquals("scope must be player or world, not \"everyone\"",
                fails("id: q\ntitle: T\nscope: everyone\n"));
    }

    @Test
    void requiresMustBeList() {
        assertTrue(fails(HEADER + "requires: other\n").startsWith("requires must be a list"));
    }

    @Test
    void unknownTopLevelKeyWarns() throws IOException {
        load(HEADER + "colour: red\n");
        assertEquals(List.of("top level: unknown key \"colour\" (ignored)"), warnings);
    }

    // ---- facts ----

    @Test
    void factsMustBeMap() {
        assertEquals("facts must be a map of names to values", fails(HEADER + "facts: [a, b]\n"));
    }

    @Test
    void rumourWithoutText() {
        assertEquals("facts.f: rumours.r: no text:", fails(HEADER + """
                facts:
                  f:
                    rumours:
                      r:
                        spreads-to: [nuns]
                """));
    }

    @Test
    void unknownFactKeyWarns() throws IOException {
        // the old rumour format, which isn't read
        load(HEADER + """
                facts:
                  f:
                    spreads-to: [nuns]
                """);
        assertEquals(List.of("facts.f: unknown key \"spreads-to\" (ignored)"), warnings);
    }

    // ---- triggers ----

    @Test
    void triggersMustBeList() {
        assertTrue(fails(HEADER + "triggers:\n  on: read-book x\n").startsWith("triggers must be a list"));
    }

    @Test
    void triggerMustBeMap() {
        // "on:" forgotten, so the item is a string
        String msg = fails(HEADER + "triggers:\n  - read-book x\n");
        assertTrue(msg.startsWith("triggers #1 must be a map"), msg);
    }

    @Test
    void triggerWithoutCondition() {
        assertEquals("triggers #1: no on: condition", fails(HEADER + """
                facts:
                  f: {}
                triggers:
                  - do:
                      - set: f
                """));
    }

    @Test
    void triggerUnknownCondition() {
        String msg = fails(HEADER + """
                facts:
                  f: {}
                triggers:
                  - on: read-book x
                    do:
                      - set: f
                  - on: dance with helga
                    do:
                      - set: f
                """);
        assertTrue(msg.startsWith("triggers #2: on: unknown condition type \"dance\""), msg);
    }

    @Test
    void triggerBadReadBook() {
        String msg = fails(HEADER + """
                facts:
                  f: {}
                triggers:
                  - on: read-book two words
                    do:
                      - set: f
                """);
        assertTrue(msg.startsWith("triggers #1: on: bad read-book condition"), msg);
    }

    @Test
    void triggerWithoutActions() {
        String msg = fails(HEADER + """
                triggers:
                  - on: read-book x
                """);
        assertTrue(msg.startsWith("triggers #1: no do: actions"), msg);
    }

    @Test
    void triggerWithOldStyleSet() {
        // set: directly on the trigger, as it used to be, rather than in a do: list
        String msg = fails(HEADER + """
                facts:
                  f: {}
                triggers:
                  - on: read-book x
                    set: f
                """);
        assertTrue(msg.startsWith("triggers #1: no do: actions"), msg);
        assertEquals(List.of("triggers #1: unknown key \"set\" (ignored)"), warnings);
    }

    // ---- action lists (do:), tested through triggers ----

    @Test
    void doSeveralActions() throws IOException {
        load(HEADER + """
                facts:
                  f: {}
                  g: {}
                  h: {}
                triggers:
                  - on: read-book x
                    do:
                      - set: f
                      - set: [g, h]
                """);
        assertEquals(List.of(), warnings);
    }

    @Test
    void doMustBeList() {
        String msg = fails(HEADER + """
                facts:
                  f: {}
                triggers:
                  - on: read-book x
                    do:
                      set: f
                """);
        assertTrue(msg.startsWith("triggers #1: do must be a list"), msg);
    }

    @Test
    void doItemMustBeMap() {
        String msg = fails(HEADER + """
                facts:
                  f: {}
                triggers:
                  - on: read-book x
                    do:
                      - set f
                """);
        assertTrue(msg.startsWith("triggers #1: do #1 must be a map"), msg);
    }

    @Test
    void doMissingDash() {
        // the second action has no "-", so the two are one item with two keys
        String msg = fails(HEADER + """
                facts:
                  f: {}
                triggers:
                  - on: read-book x
                    do:
                      - set: f
                        give: PAPER
                """);
        assertTrue(msg.startsWith("triggers #1: do #1 should be one action, but has [set, give]"), msg);
        assertTrue(msg.contains("\"- \""), msg);
    }

    @Test
    void doUnknownAction() {
        assertEquals("triggers #1: do #2: unknown action \"dance\", should be set", fails(HEADER + """
                facts:
                  f: {}
                triggers:
                  - on: read-book x
                    do:
                      - set: f
                      - dance: wildly
                """));
    }

    @Test
    void doSetUnknownFact() {
        assertEquals("triggers #1: do #1: set: there is no fact called \"g\"", fails(HEADER + """
                facts:
                  f: {}
                triggers:
                  - on: read-book x
                    do:
                      - set: g
                """));
    }

    @Test
    void doSetUnknownFactInList() {
        assertEquals("triggers #1: do #1: set: there is no fact called \"h\"", fails(HEADER + """
                facts:
                  f: {}
                  g: {}
                triggers:
                  - on: read-book x
                    do:
                      - set: [f, h]
                """));
    }

    @Test
    void doSetEmptyList() {
        assertEquals("triggers #1: do #1: set: no facts given", fails(HEADER + """
                facts:
                  f: {}
                triggers:
                  - on: read-book x
                    do:
                      - set: []
                """));
    }

    @Test
    void doSetMap() {
        assertEquals("triggers #1: do #1: set: should be a fact or a list of facts, not a map", fails(HEADER + """
                facts:
                  f: {}
                triggers:
                  - on: read-book x
                    do:
                      - set: { f: true }
                """));
    }

    // ---- participants ----

    @Test
    void participantMustBeList() {
        String msg = fails(HEADER + """
                participants:
                  helga:
                    when: x
                """);
        assertTrue(msg.startsWith("participants.helga must be a list"), msg);
    }

    @Test
    void participantWithoutWhen() {
        assertEquals("participants.helga #1: no when:", fails(HEADER + """
                participants:
                  helga:
                    - prompt: hello
                """));
    }

    @Test
    void participantBadWhen() {
        String msg = fails(HEADER + """
                facts:
                  f: {}
                participants:
                  helga:
                    - when: f
                      prompt: hello
                    - when: f and g
                      prompt: hello
                """);
        assertTrue(msg.startsWith("participants.helga #2: when: "), msg);
        assertTrue(msg.contains("\"f and g\""), msg);
    }

    @Test
    void participantWithNothingToDoWarns() throws IOException {
        load(HEADER + """
                facts:
                  f: {}
                participants:
                  helga:
                    - when: f
                """);
        assertEquals(List.of("participants.helga #1: no prompt: or actions:, so this does nothing"), warnings);
    }

    // ---- NPC actions ----

    @Test
    void actionWithoutPromptIsFine() throws IOException {
        load(HEADER + """
                facts:
                  f: {}
                participants:
                  helga:
                    - when: not f
                      actions:
                        confess: { set: f, desc: "You confess" }
                """);
        assertEquals(List.of(), warnings);
    }

    @Test
    void actionSetsUnknownFact() {
        assertEquals("participants.helga #1: actions.confess: set: there is no fact called \"g\"", fails(HEADER + """
                facts:
                  f: {}
                participants:
                  helga:
                    - when: f
                      actions:
                        confess: { set: [f, g] }
                """));
    }

    @Test
    void actionWithoutSet() {
        String msg = fails(HEADER + """
                facts:
                  f: {}
                participants:
                  helga:
                    - when: f
                      actions:
                        confess: { desc: "You confess" }
                """);
        assertTrue(msg.startsWith("participants.helga #1: actions.confess: no set:"), msg);
    }

    @Test
    void actionWithoutDesc() {
        String msg = fails(HEADER + """
                facts:
                  f: {}
                participants:
                  helga:
                    - when: f
                      actions:
                        confess: { set: f }
                """);
        assertTrue(msg.startsWith("participants.helga #1: actions.confess: no desc:"), msg);
    }

    @Test
    void actionWithBlankDesc() {
        String msg = fails(HEADER + """
                facts:
                  f: {}
                participants:
                  helga:
                    - when: f
                      actions:
                        confess: { set: f, desc: "  " }
                """);
        assertTrue(msg.startsWith("participants.helga #1: actions.confess: no desc:"), msg);
    }

    @Test
    void actionMustBeMap() {
        String msg = fails(HEADER + """
                facts:
                  f: {}
                participants:
                  helga:
                    - when: f
                      actions:
                        confess: f
                """);
        assertTrue(msg.startsWith("participants.helga #1: actions.confess: must contain set:"), msg);
    }

    @Test
    void actionsMustBeMap() {
        String msg = fails(HEADER + """
                facts:
                  f: {}
                participants:
                  helga:
                    - when: f
                      actions: [confess]
                """);
        assertTrue(msg.startsWith("participants.helga #1: actions must be a map"), msg);
    }

    @Test
    void unknownActionKeyWarns() throws IOException {
        load(HEADER + """
                facts:
                  f: {}
                participants:
                  helga:
                    - when: f
                      actions:
                        confess: { set: f, desc: "You confess", colour: red }
                """);
        assertEquals(List.of("participants.helga #1.actions.confess: unknown key \"colour\" (ignored)"),
                warnings);
    }

    // ---- objectives and complete-when ----

    @Test
    void objectiveWithoutText() {
        assertEquals("objectives #1: no text:", fails(HEADER + """
                facts:
                  f: {}
                objectives:
                  - done: f
                """));
    }

    @Test
    void objectiveWithoutDone() {
        assertEquals("objectives #1: no done:", fails(HEADER + """
                objectives:
                  - text: Do something
                """));
    }

    @Test
    void objectiveBadDone() {
        String msg = fails(HEADER + """
                facts:
                  f: {}
                objectives:
                  - text: Do something
                    done: f
                  - text: Do something else
                    done: g
                """);
        assertTrue(msg.startsWith("objectives #2: done: "), msg);
        assertTrue(msg.contains("\"g\""), msg);
    }

    @Test
    void objectiveMustBeMap() {
        String msg = fails(HEADER + "objectives:\n  - Do something\n");
        assertTrue(msg.startsWith("objectives #1 must be a map"), msg);
    }

    @Test
    void badCompleteWhen() {
        String msg = fails(HEADER + """
                facts:
                  f: {}
                complete-when: f and
                """);
        assertTrue(msg.startsWith("complete-when: "), msg);
        assertTrue(msg.contains("\"f and\""), msg);
    }

    @Test
    void warningsKeptWhenLoadingFails() {
        // the typo'd key explains the error, so the warning about it mustn't be lost
        fails(HEADER + """
                participants:
                  helga:
                    - whn: f
                      prompt: hello
                """);
        assertEquals(List.of("participants.helga #1: unknown key \"whn\" (ignored)"), warnings);
    }
}
