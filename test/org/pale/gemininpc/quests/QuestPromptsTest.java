package org.pale.gemininpc.quests;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/// Which quest prompts an NPC gets when talking to a player, using the example quest and the NPCs as they
/// are on the test server (some named in the quest by their persona).
public class QuestPromptsTest {
    @TempDir
    Path dir;

    static final int LADY_CHANTER = 1, PRUNE = 2, HELGA = 3, ANGREN = 4, AGNES = 5;

    final QuestRegistry reg = new QuestRegistry(new QuestStates());
    final QuestDebug debug = new QuestDebug(reg);
    final UUID alice = UUID.randomUUID();
    final UUID bob = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        QuestNPCs.setDirectory(() -> List.of(
                new QuestNPCs.NPCInfo(LADY_CHANTER, "Lady Chanter", "ladychanter", Set.of("nuns")),
                new QuestNPCs.NPCInfo(PRUNE, "Prunesquallor", "prune"),
                new QuestNPCs.NPCInfo(HELGA, "Helga", "helga", Set.of("nuns")),
                new QuestNPCs.NPCInfo(ANGREN, "Angren", "lofgar"),
                new QuestNPCs.NPCInfo(AGNES, "Sister Agnes", "nun", Set.of("nuns"))));
        reg.add(new Quest(Path.of("exampleconfig/quests/banner.yml"), new ArrayList<>()), "banner.yml");
    }

    @AfterEach
    void resetDirectory() {
        QuestNPCs.setDirectory(List::of);
    }

    private void set(UUID player, String fact) {
        debug.set(player, "banner", fact, "true");
    }

    /// Assert that each prompt starts with the corresponding text, in order.
    private static void assertPrompts(List<String> prompts, String... starts) {
        assertEquals(starts.length, prompts.size(), prompts.toString());
        for (int i = 0; i < starts.length; i++)
            assertTrue(prompts.get(i).startsWith(starts[i]), "prompt " + i + ": " + prompts.get(i));
    }

    @Test
    void atTheStart() {
        assertPrompts(reg.promptsFor(LADY_CHANTER, alice), "You've heard an ominous banner");
        assertPrompts(reg.promptsFor(HELGA, alice), "You secretly find the pillagers exciting");
        assertPrompts(reg.promptsFor(PRUNE, alice));    // nothing yet
        assertPrompts(reg.promptsFor(ANGREN, alice));
    }

    @Test
    void npcNotInTheQuestGetsNothing() {
        assertPrompts(reg.promptsFor(AGNES, alice));
    }

    @Test
    void afterTheBannerIsRecovered() {
        set(alice, "banner_recovered");
        assertPrompts(reg.promptsFor(LADY_CHANTER, alice), "The player has brought you the ominous banner");
        assertPrompts(reg.promptsFor(PRUNE, alice), "Some nights ago you saw Helga");
    }

    @Test
    void secretsStayWithTheirStage() {
        // before the player knows the name, nothing tells Prunesquallor or Helga about Lofgar
        set(alice, "banner_recovered");
        set(alice, "helga_confessed");
        for (int npc : new int[]{PRUNE, HELGA, ANGREN})
            for (String p : reg.promptsFor(npc, alice))
                assertFalse(p.contains("Lofgar"), p);
        set(alice, "knows_lofgar");
        assertTrue(reg.promptsFor(PRUNE, alice).stream().anyMatch(p -> p.contains("Angren") && p.contains("Lofgar")));
        assertTrue(reg.promptsFor(HELGA, alice).stream().anyMatch(p -> p.contains("Lofgar")));
    }

    @Test
    void severalConditionsInFileOrder() {
        set(alice, "banner_recovered");
        set(alice, "knows_lofgar");
        // both of Prunesquallor's conditions are true
        assertPrompts(reg.promptsFor(PRUNE, alice), "Some nights ago", "You keep a secret");
    }

    @Test
    void promptsArePerPlayer() {
        set(alice, "banner_recovered");
        assertPrompts(reg.promptsFor(LADY_CHANTER, bob), "You've heard an ominous banner");
    }

    @Test
    void completedQuestStillGivesPrompts() {
        // revealing Lofgar completes the quest; Angren must still get his prompt
        set(alice, "lofgar_revealed");
        assertTrue(reg.isComplete(reg.get("banner"), alice));
        assertPrompts(reg.promptsFor(ANGREN, alice), "Your secret is out");
    }

    @Test
    void noPlayerMeansOnlyWorldQuests() {
        // the banner quest is a player quest, so with no player there's no guidance, and no state is made
        assertPrompts(reg.promptsFor(LADY_CHANTER, null));
        assertNull(reg.getStates().get(reg.get("banner"), null));
    }

    @Test
    void questNotStartedGivesNothing() throws IOException {
        Path p = dir.resolve("sequel.yml");
        Files.writeString(p, """
                id: sequel
                title: Sequel
                scope: player
                requires: [banner]
                facts:
                  f: {}
                participants:
                  prune:
                    - when: not f
                      prompt: Sequel prompt
                """);
        reg.add(new Quest(p, new ArrayList<>()), "sequel.yml");
        assertPrompts(reg.promptsFor(PRUNE, alice));
        set(alice, "lofgar_revealed");      // completes banner, so the sequel starts
        assertTrue(reg.promptsFor(PRUNE, alice).contains("Sequel prompt"));
    }

    // ---- rumours ----

    @Test
    void rumourReachesTheGroupOnceItsFactIsTrue() {
        assertPrompts(reg.promptsFor(AGNES, alice));
        set(alice, "helga_exposed");
        assertPrompts(reg.promptsFor(AGNES, alice), "It is whispered that Helga hung the banner");
        // Prunesquallor isn't a nun
        assertFalse(reg.promptsFor(PRUNE, alice).stream().anyMatch(p -> p.startsWith("It is whispered")));
    }

    @Test
    void rumourSkipsThoseExcepted() {
        // Helga and the Lady Chanter are nuns, but the banner quest's rumour leaves them out
        set(alice, "helga_exposed");
        for (int npc : new int[]{HELGA, LADY_CHANTER})
            assertFalse(reg.promptsFor(npc, alice).stream().anyMatch(p -> p.startsWith("It is whispered")));
    }

    @Test
    void exceptCanNameAGroup() throws IOException {
        Path p = dir.resolve("gossip.yml");
        Files.writeString(p, """
                id: gossip
                title: Gossip
                scope: world
                facts:
                  f:
                    rumours:
                      r:
                        spreads-to: [sister agnes, prune]
                        except: [nuns]
                        text: Whisper
                """);
        reg.add(new Quest(p, new ArrayList<>()), "gossip.yml");
        debug.set(alice, "gossip", "f", "true");
        assertPrompts(reg.promptsFor(AGNES, alice));
        assertTrue(reg.promptsFor(PRUNE, alice).contains("Whisper"));
    }

    @Test
    void rumourIsPerPlayer() {
        set(alice, "helga_exposed");
        assertPrompts(reg.promptsFor(AGNES, bob));
    }

    @Test
    void rumourComesAfterTheNPCsOwnPrompts() throws IOException {
        Path p = dir.resolve("gossip.yml");
        Files.writeString(p, """
                id: gossip
                title: Gossip
                scope: world
                facts:
                  f:
                    rumours:
                      first:
                        spreads-to: [nuns, sister agnes]    # Agnes twice, but she hears it once
                        text: First rumour
                      second:
                        spreads-to: [prune]
                        text: Not for Agnes
                      third:
                        spreads-to: [nuns]
                        text: Third rumour
                participants:
                  sister agnes:
                    - when: f
                      prompt: Agnes's own
                """);
        reg.add(new Quest(p, new ArrayList<>()), "gossip.yml");
        debug.set(alice, "gossip", "f", "true");
        // a world quest's rumour reaches everyone, even with no player
        for (UUID player : new UUID[]{alice, bob, null})
            assertPrompts(reg.promptsFor(AGNES, player), "Agnes's own", "First rumour", "Third rumour");
    }

    @Test
    void noRumourMakesNoState() {
        // asking about a nun who's no participant mustn't create (and so save) state for the player
        reg.promptsFor(AGNES, bob);
        assertNull(reg.getStates().get(reg.get("banner"), bob));
    }
}
