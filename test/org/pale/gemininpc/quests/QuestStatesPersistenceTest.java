package org.pale.gemininpc.quests;

import org.bukkit.configuration.InvalidConfigurationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pale.gemininpc.quests.expr.Value;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/// Saving quest state to a file and loading it back, as across a server restart.
public class QuestStatesPersistenceTest {
    @TempDir
    Path dir;

    final UUID alice = UUID.randomUUID();
    final UUID bob = UUID.randomUUID();

    static final String PLAYER_QUEST = """
            id: first
            title: First
            scope: player
            facts:
              a: {}
              b: {}
            complete-when: a and b
            """;

    static final String WORLD_QUEST = """
            id: town
            title: Town
            scope: world
            facts:
              saved: {}
            """;

    private Quest quest(String yaml) throws IOException {
        Path p = Files.createTempFile(dir, "quest", ".yml");
        Files.writeString(p, yaml);
        return new Quest(p, new ArrayList<>());
    }

    private Path stateFile() {
        return dir.resolve("queststate.yml");
    }

    /// Save `states`, then load the file into a fresh `QuestStates`, as a restart would.
    private QuestStates saveAndReload(QuestStates states) throws Exception {
        states.save(stateFile());
        QuestStates fresh = new QuestStates();
        assertEquals(List.of(), fresh.load(stateFile()));
        return fresh;
    }

    @Test
    void roundTrip() throws Exception {
        Quest first = quest(PLAYER_QUEST);
        Quest town = quest(WORLD_QUEST);
        QuestStates states = new QuestStates();
        states.getOrCreate(first, alice).set("a", Value.TRUE);
        Instance b = states.getOrCreate(first, bob);
        b.set("a", Value.TRUE);
        b.set("b", Value.TRUE);
        b.completed = true;
        states.getOrCreate(town, alice).set("saved", Value.TRUE);

        QuestStates loaded = saveAndReload(states);
        Instance a2 = loaded.get(first, alice);
        assertEquals(Value.TRUE, a2.get("a"));
        assertEquals(Value.FALSE, a2.get("b"));
        assertFalse(a2.isCompleted());
        assertTrue(loaded.get(first, bob).isCompleted());
        // the world quest's state is shared, whoever asks
        assertEquals(Value.TRUE, loaded.get(town, bob).get("saved"));
    }

    @Test
    void missingFileMeansNoState() throws Exception {
        QuestStates states = new QuestStates();
        assertEquals(List.of(), states.load(stateFile()));
        assertNull(states.get(quest(PLAYER_QUEST), alice));
    }

    @Test
    void stateForUnloadedQuestIsKept() throws Exception {
        Quest first = quest(PLAYER_QUEST);
        QuestStates states = new QuestStates();
        states.getOrCreate(first, alice).set("a", Value.TRUE);

        // restart with the quest's file broken, so it never asks for its state, and save again
        QuestStates whileBroken = saveAndReload(states);
        QuestStates afterFix = saveAndReload(whileBroken);
        // the quest file is fixed: the progress is still there
        assertEquals(Value.TRUE, afterFix.get(first, alice).get("a"));
    }

    @Test
    void loadWithChangedFacts() throws Exception {
        QuestStates states = new QuestStates();
        Instance inst = states.getOrCreate(quest(PLAYER_QUEST), alice);
        inst.set("a", Value.TRUE);
        inst.set("b", Value.TRUE);
        QuestStates loaded = saveAndReload(states);
        // the quest has since changed: b was removed and c added
        Quest changed = quest("""
                id: first
                title: First
                scope: player
                facts:
                  a: {}
                  c: {}
                """);
        Instance i2 = loaded.get(changed, alice);
        assertEquals(Value.TRUE, i2.get("a"));
        assertEquals(Value.FALSE, i2.get("c"));
        assertThrows(IllegalArgumentException.class, () -> i2.get("b"));
    }

    @Test
    void resetRemovesSavedState() throws Exception {
        Quest first = quest(PLAYER_QUEST);
        QuestStates states = new QuestStates();
        states.getOrCreate(first, alice).set("a", Value.TRUE);
        QuestStates loaded = saveAndReload(states);
        loaded.reset(first, alice);     // before the quest has asked for it
        assertNull(loaded.get(first, alice));
        assertNull(saveAndReload(loaded).get(first, alice));
    }

    @Test
    void otherValueTypes() throws Exception {
        Quest first = quest(PLAYER_QUEST);
        QuestStates states = new QuestStates();
        Instance inst = states.getOrCreate(first, alice);
        inst.set("a", new Value.Int(42));
        inst.set("b", new Value.Str("hello"));
        Instance i2 = saveAndReload(states).get(first, alice);
        assertEquals(new Value.Int(42), i2.get("a"));
        assertEquals(new Value.Str("hello"), i2.get("b"));
    }

    @Test
    void questIdWithDots() throws Exception {
        // Bukkit would normally treat the dot as a path separator
        Quest dotted = quest(PLAYER_QUEST.replace("id: first", "id: chapter.one"));
        QuestStates states = new QuestStates();
        states.getOrCreate(dotted, alice).set("a", Value.TRUE);
        assertEquals(Value.TRUE, saveAndReload(states).get(dotted, alice).get("a"));
    }

    @Test
    void badEntriesAreSkippedWithWarnings() throws Exception {
        Files.writeString(stateFile(), """
                first:
                  not-a-uuid:
                    completed: false
                  %s:
                    completed: true
                    facts:
                      a: [1, 2]
                      b: true
                """.formatted(alice));
        QuestStates states = new QuestStates();
        List<String> warnings = states.load(stateFile());
        assertEquals(2, warnings.size(), warnings.toString());
        Instance inst = states.get(quest(PLAYER_QUEST), alice);
        assertTrue(inst.isCompleted());
        assertEquals(Value.FALSE, inst.get("a"));   // skipped, so it has its initial value
        assertEquals(Value.TRUE, inst.get("b"));
    }

    @Test
    void corruptFileThrowsAndKeepsState() throws Exception {
        Quest first = quest(PLAYER_QUEST);
        QuestStates states = new QuestStates();
        states.getOrCreate(first, alice).set("a", Value.TRUE);
        Files.writeString(stateFile(), "first: [unclosed");
        assertThrows(InvalidConfigurationException.class, () -> states.load(stateFile()));
        assertEquals(Value.TRUE, states.get(first, alice).get("a"));
    }

    @Test
    void saveLeavesNoTemporaryFile() throws Exception {
        QuestStates states = new QuestStates();
        states.getOrCreate(quest(PLAYER_QUEST), alice).set("a", Value.TRUE);
        states.save(stateFile());
        assertTrue(Files.exists(stateFile()));
        assertFalse(Files.exists(dir.resolve("queststate.yml.tmp")));
    }
}
