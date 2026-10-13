package org.pale.gemininpc.quests;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

/// Quest actions: which ones an NPC is offered, and running the one the LLM chooses. Uses the example quest
/// and the NPCs as they are on the test server.
public class QuestActionsTest {
    @TempDir
    Path dir;

    static final int LADY_CHANTER = 1, PRUNE = 2, HELGA = 3, ANGREN = 4;

    final QuestRegistry reg = new QuestRegistry(new QuestStates());
    final QuestDebug debug = new QuestDebug(reg);
    final UUID alice = UUID.randomUUID();
    final UUID bob = UUID.randomUUID();
    final List<String> log = new ArrayList<>();

    @BeforeEach
    void setUp() {
        QuestNPCs.setDirectory(() -> List.of(
                new QuestNPCs.NPCInfo(LADY_CHANTER, "Lady Chanter", "ladychanter"),
                new QuestNPCs.NPCInfo(PRUNE, "Prunesquallor", "prune"),
                new QuestNPCs.NPCInfo(HELGA, "Helga", "helga"),
                new QuestNPCs.NPCInfo(ANGREN, "Angren", "lofgar")));
        reg.add(new Quest(Path.of("exampleconfig/quests/banner.yml"), new ArrayList<>()), "banner.yml");
    }

    @AfterEach
    void resetDirectory() {
        QuestNPCs.setDirectory(List::of);
    }

    private List<String> offered(int npc, UUID player) {
        return reg.actionsFor(npc, player).stream().map(QuestRegistry.OfferedAction::name).toList();
    }

    private QuestRegistry.ActionOutcome run(int npc, UUID player, String name) {
        return reg.runNPCAction(npc, null, player, null, name, log);
    }

    private Value fact(UUID player, String name) {
        return reg.getStates().get(reg.get("banner"), player).get(name);
    }

    @Test
    void offeredAtTheStart() {
        assertEquals(List.of("confess"), offered(HELGA, alice));
        assertEquals(List.of(), offered(PRUNE, alice));
        assertEquals(List.of(), offered(LADY_CHANTER, alice));
    }

    @Test
    void offeredActionHasItsDescription() {
        var a = reg.actionsFor(HELGA, alice).getFirst();
        assertEquals("You admit to the player that you hung the banner", a.desc());
    }

    @Test
    void runningAnActionSetsItsFacts() {
        debug.set(alice, "banner", "banner_recovered", "true");
        assertEquals(List.of("tell_about_helga"), offered(PRUNE, alice));
        assertEquals(QuestRegistry.ActionOutcome.RAN, run(PRUNE, alice, "tell_about_helga"));
        assertEquals(Value.TRUE, fact(alice, "prune_consulted"));
        assertEquals(Value.TRUE, fact(alice, "helga_exposed"));
        assertEquals(List.of("banner action tell_about_helga"), log);
        // which moves the Lady Chanter on
        assertTrue(reg.promptsFor(LADY_CHANTER, alice).getFirst().startsWith("The player may have found out who hung"));
    }

    @Test
    void actionNotOfferedNowIsIgnored() {
        // Prunesquallor can't tell about Helga before the banner is recovered
        assertEquals(QuestRegistry.ActionOutcome.NOT_OFFERED, run(PRUNE, alice, "tell_about_helga"));
        assertEquals(Value.FALSE, fact(alice, "helga_exposed"));
        assertEquals(List.of("banner action tell_about_helga isn't offered now, so it was ignored"), log);
    }

    @Test
    void anotherNPCsActionIsNotAQuestActionForThisOne() {
        // "confess" is Helga's, not Prunesquallor's
        assertEquals(QuestRegistry.ActionOutcome.NOT_QUEST_ACTION, run(PRUNE, alice, "confess"));
    }

    @Test
    void unknownNameIsNotAQuestAction() {
        assertEquals(QuestRegistry.ActionOutcome.NOT_QUEST_ACTION, run(HELGA, alice, "give"));
        assertEquals(List.of(), log);
    }

    @Test
    void actionAppliesToThePartnerOnly() {
        run(HELGA, alice, "confess");
        assertEquals(Value.TRUE, fact(alice, "helga_confessed"));
        assertEquals(List.of("confess"), offered(HELGA, bob));      // Bob's Helga hasn't confessed
        assertEquals(List.of(), offered(HELGA, alice));             // and Alice's can't again
    }

    @Test
    void actionCanCompleteTheQuest() {
        debug.set(alice, "banner", "knows_lofgar", "true");
        assertEquals(QuestRegistry.ActionOutcome.RAN, run(PRUNE, alice, "reveal_angren"));
        assertTrue(reg.isComplete(reg.get("banner"), alice));
        assertEquals(List.of("banner action reveal_angren", "banner completed"), log);
        // a complete quest offers no actions
        assertEquals(List.of(), offered(PRUNE, alice));
        assertEquals(QuestRegistry.ActionOutcome.NOT_OFFERED, run(PRUNE, alice, "reveal_angren"));
    }

    @Test
    void actionNameMustBeOneWord() throws IOException {
        Path p = dir.resolve("q.yml");
        Files.writeString(p, """
                id: q
                title: Q
                scope: player
                facts:
                  f: {}
                participants:
                  helga:
                    - when: f
                      actions:
                        tell all:
                          desc: You tell all
                          do:
                            - set: f
                """);
        QuestException e = assertThrows(QuestException.class, () -> new Quest(p, new ArrayList<>()));
        assertEquals("participants.helga #1: actions.tell all: an action's name must be a single word",
                e.getMessage());
    }

    @Test
    void clashWithBuiltInActionWarns() {
        assertEquals(List.of("banner.yml: participants.helga: action \"confess\" has the same name as a "
                + "built-in action; rename it"), reg.checkActionNames(List.of("give", "confess")));
        assertEquals(List.of(), reg.checkActionNames(List.of("give", "go")));
    }
}
