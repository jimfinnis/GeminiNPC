package org.pale.gemininpc.quests;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/// What the `/gemini quest` debugging commands show and do.
public class QuestDebugTest {
    @TempDir
    Path dir;

    final QuestRegistry reg = new QuestRegistry(new QuestStates());
    final QuestDebug debug = new QuestDebug(reg);
    final UUID alice = UUID.randomUUID();
    final UUID bob = UUID.randomUUID();

    private void add(String fileName, String yaml) throws IOException {
        Path p = dir.resolve(fileName);
        Files.writeString(p, yaml);
        reg.add(new Quest(p, new ArrayList<>()), fileName);
    }

    @BeforeEach
    void loadQuests() throws IOException {
        add("first.yml", """
                id: first
                title: The First
                scope: player
                facts:
                  a: {}
                  b: {}
                complete-when: a and b
                """);
        add("second.yml", """
                id: second
                title: The Second
                scope: player
                requires: [first]
                facts:
                  c: {}
                """);
        add("town.yml", """
                id: town
                title: The Town
                scope: world
                facts:
                  saved: {}
                """);
    }

    @Test
    void list() {
        assertEquals(List.of(
                "first: The First (player quest)",
                "second: The Second (player quest, requires first)",
                "town: The Town (world quest)"), debug.list());
    }

    @Test
    void stateAtTheStart() {
        assertEquals(List.of(
                "first: active, no progress yet",
                "second: not active yet; waiting for first",
                "town (world): active, no progress yet"), debug.state(alice));
    }

    @Test
    void setShowsFactsInFileOrder() {
        assertEquals(List.of("first: set b=true", "  a=false, b=true"), debug.set(alice, "first", "b", "true"));
        assertEquals(List.of(
                "first: active",
                "  a=false, b=true",
                "second: not active yet; waiting for first",
                "town (world): active, no progress yet"), debug.state(alice));
    }

    @Test
    void setCanCompleteAQuest() {
        debug.set(alice, "first", "a", "true");
        List<String> lines = debug.set(alice, "first", "b", "yes");
        assertTrue(lines.contains("first is now complete."), lines.toString());
        assertTrue(reg.isActive(reg.get("second"), alice));
    }

    @Test
    void setOnInactiveQuestWarns() {
        List<String> lines = debug.set(alice, "second", "c", "true");
        assertTrue(lines.contains("Note: the quest isn't active yet, so its triggers and prompts won't apply."),
                lines.toString());
    }

    @Test
    void setErrors() {
        assertEquals("there is no quest \"nope\"",
                assertThrows(QuestException.class, () -> debug.set(alice, "nope", "a", "true")).getMessage());
        assertEquals("quest first has no fact \"z\"; its facts are a, b",
                assertThrows(QuestException.class, () -> debug.set(alice, "first", "z", "true")).getMessage());
        assertEquals("the value should be true or false, not \"maybe\"",
                assertThrows(QuestException.class, () -> debug.set(alice, "first", "a", "maybe")).getMessage());
    }

    @Test
    void worldQuestStateIsShared() {
        debug.set(alice, "town", "saved", "true");
        assertTrue(debug.state(bob).contains("  saved=true"));
    }

    @Test
    void resetOneQuest() {
        debug.set(alice, "first", "a", "true");
        assertEquals(List.of("Reset first"), debug.reset(alice, "first"));
        assertEquals("first: active, no progress yet", debug.state(alice).getFirst());
    }

    @Test
    void resetAllLeavesWorldQuestsAlone() {
        debug.set(alice, "first", "a", "true");
        debug.set(alice, "town", "saved", "true");
        assertEquals(List.of("Reset first", "Reset second"), debug.reset(alice, null));
        assertTrue(debug.state(alice).contains("  saved=true"));
    }

    @Test
    void resetWorldQuestByName() {
        debug.set(alice, "town", "saved", "true");
        assertEquals(List.of("Reset town for everyone, since it's a world quest"), debug.reset(bob, "town"));
        assertEquals("town (world): active, no progress yet", debug.state(alice).getLast());
    }

    @Test
    void stateWaitingForMissingQuest() throws IOException {
        add("lonely.yml", """
                id: lonely
                title: Lonely
                scope: player
                requires: [ghost]
                """);
        assertEquals("lonely: not active yet; waiting for ghost (which doesn't exist)", debug.state(alice).getLast());
    }
}
