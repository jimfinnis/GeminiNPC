package org.pale.gemininpc.quests;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pale.gemininpc.quests.expr.Value;
import org.pale.gemininpc.quests.triggers.TriggerEvent;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/// Quest state: which quests are active for a player, triggers changing facts, completion, world and player
/// scope, and state surviving a reload.
public class QuestStateTest {
    @TempDir
    Path dir;

    final List<String> warnings = new ArrayList<>();
    final QuestStates states = new QuestStates();
    QuestRegistry reg;

    final Player alice = fakePlayer("alice");
    final Player bob = fakePlayer("bob");

    /// A stand-in for a Bukkit player which only knows its UUID and name; nothing else is used here.
    static Player fakePlayer(String name) {
        UUID id = UUID.nameUUIDFromBytes(name.getBytes());
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> id;
                    case "getName" -> name;
                    case "hashCode" -> id.hashCode();
                    case "equals" -> proxy == args[0];
                    case "toString" -> "Player " + name;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    static TriggerEvent read(String book) {
        return new TriggerEvent.ReadBook(book, null);
    }

    // Two quests: "first" is done by reading two books; "second" requires it, and is done by reading a
    // third. The scope is filled in by each test.
    static String first(String scope) {
        return """
                id: first
                title: First
                scope: %s
                facts:
                  a: {}
                  b: {}
                triggers:
                  - on: read-book "Book A"
                    do:
                      - set: a
                  - on: read-book "Book B"
                    do:
                      - set: b
                complete-when: a and b
                """.formatted(scope);
    }

    static final String SECOND = """
            id: second
            title: Second
            scope: player
            requires: [first]
            facts:
              c: {}
            triggers:
              - on: read-book "Book C"
                do:
                  - set: c
            complete-when: c
            """;

    /// Load quests into a new registry sharing `states`, as `Plugin` does on each config load.
    private QuestRegistry load(String... yamls) throws IOException {
        reg = new QuestRegistry(states);
        int i = 0;
        for (String yaml : yamls) {
            Path p = dir.resolve("q" + (i++) + ".yml");
            Files.writeString(p, yaml);
            reg.add(new Quest(p, warnings), p.getFileName().toString());
        }
        return reg;
    }

    private Quest q(String id) {
        return reg.get(id);
    }

    private UUID id(Player p) {
        return p.getUniqueId();
    }

    @BeforeEach
    void loadQuests() throws IOException {
        load(first("player"), SECOND);
    }

    // ---- which quests are active ----

    @Test
    void questWithoutRequiresIsActiveFromTheStart() {
        assertTrue(reg.isActive(q("first"), id(alice)));
        assertEquals(List.of(q("first")), reg.activeQuests(id(alice)));
    }

    @Test
    void questIsInactiveUntilWhatItRequiresIsComplete() {
        assertFalse(reg.isActive(q("second"), id(alice)));
        assertNull(reg.activeInstance(q("second"), id(alice)));
    }

    @Test
    void unknownRequirementMeansNeverActive() throws IOException {
        load("""
                id: lonely
                title: Lonely
                scope: player
                requires: [nonexistent]
                """);
        assertFalse(reg.isActive(q("lonely"), id(alice)));
    }

    // ---- triggers and completion ----

    @Test
    void triggerSetsFact() {
        assertTrue(reg.fire(alice, read("Book A")));
        Instance inst = states.get(q("first"), id(alice));
        assertEquals(Value.TRUE, inst.get("a"));
        assertEquals(Value.FALSE, inst.get("b"));
        assertFalse(inst.isCompleted());
    }

    @Test
    void unmatchedEventDoesNothing() {
        assertFalse(reg.fire(alice, read("Some Other Book")));
    }

    @Test
    void completingAQuestActivatesTheNext() {
        reg.fire(alice, read("Book A"));
        reg.fire(alice, read("Book B"));
        assertTrue(reg.isComplete(q("first"), id(alice)));
        assertFalse(reg.isActive(q("first"), id(alice)));
        assertTrue(reg.isActive(q("second"), id(alice)));
        assertEquals(List.of(q("second")), reg.activeQuests(id(alice)));
    }

    @Test
    void triggersOfInactiveQuestsDontFire() {
        // "second" isn't active yet, so reading Book C does nothing
        assertFalse(reg.fire(alice, read("Book C")));
        assertNull(states.get(q("second"), id(alice)));
    }

    @Test
    void triggersOfCompletedQuestsDontFire() {
        reg.fire(alice, read("Book A"));
        reg.fire(alice, read("Book B"));
        assertFalse(reg.fire(alice, read("Book A")));
    }

    @Test
    void newlyActiveQuestDoesntSeeTheEventThatActivatedIt() throws IOException {
        // reading the same book is the last step of one quest and the first of the next
        load("""
                id: one
                title: One
                scope: player
                facts:
                  read: {}
                triggers:
                  - on: read-book Shared
                    do:
                      - set: read
                complete-when: read
                """, """
                id: two
                title: Two
                scope: player
                requires: [one]
                facts:
                  read: {}
                triggers:
                  - on: read-book Shared
                    do:
                      - set: read
                """);
        reg.fire(alice, read("Shared"));
        assertTrue(reg.isComplete(q("one"), id(alice)));
        assertEquals(Value.FALSE, reg.activeInstance(q("two"), id(alice)).get("read"));
        // reading it again counts for the second quest
        reg.fire(alice, read("Shared"));
        assertEquals(Value.TRUE, reg.activeInstance(q("two"), id(alice)).get("read"));
    }

    // ---- scope ----

    @Test
    void playerQuestsAreSeparate() {
        reg.fire(alice, read("Book A"));
        reg.fire(alice, read("Book B"));
        assertTrue(reg.isComplete(q("first"), id(alice)));
        assertFalse(reg.isComplete(q("first"), id(bob)));
        assertFalse(reg.isActive(q("second"), id(bob)));
    }

    @Test
    void worldQuestIsShared() throws IOException {
        load(first("world"), SECOND);
        reg.fire(alice, read("Book A"));
        reg.fire(bob, read("Book B"));      // bob finishes what alice started
        assertTrue(reg.isComplete(q("first"), id(alice)));
        assertTrue(reg.isComplete(q("first"), id(bob)));
        // "second" is a player quest requiring the world quest, so it's now active for everyone
        assertTrue(reg.isActive(q("second"), id(alice)));
        assertTrue(reg.isActive(q("second"), id(bob)));
    }

    @Test
    void resetStartsAgain() {
        reg.fire(alice, read("Book A"));
        states.reset(q("first"), id(alice));
        assertEquals(Value.FALSE, reg.activeInstance(q("first"), id(alice)).get("a"));
    }

    // ---- reloading ----

    @Test
    void stateSurvivesReload() throws IOException {
        reg.fire(alice, read("Book A"));
        load(first("player"), SECOND);      // as /gemini reload does
        assertEquals(Value.TRUE, states.get(q("first"), id(alice)).get("a"));
    }

    @Test
    void reloadWithChangedFacts() throws IOException {
        reg.fire(alice, read("Book A"));
        // fact b removed, fact d added
        load("""
                id: first
                title: First
                scope: player
                facts:
                  a: {}
                  d: {}
                complete-when: a and d
                """);
        Instance inst = states.get(q("first"), id(alice));
        assertEquals(Value.TRUE, inst.get("a"));
        assertEquals(Value.FALSE, inst.get("d"));
        assertThrows(IllegalArgumentException.class, () -> inst.get("b"));
    }

    @Test
    void exampleQuestIsActiveFromTheStart() {
        Quest banner = new Quest(Path.of("exampleconfig/quests/banner.yml"), warnings);
        QuestRegistry r = new QuestRegistry(states);
        r.add(banner, "banner.yml");
        assertTrue(r.isActive(banner, id(alice)));
        // reading Helga's diary sets knows_lofgar
        assertTrue(r.fire(alice, read("Helga's Diary")));
        assertEquals(Value.TRUE, states.get(banner, id(alice)).get("knows_lofgar"));
    }
}
