package org.pale.gemininpc.quests;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/// Finding the NPC a quest means by a name: the NPC's own name first, then a persona used by only one NPC.
public class QuestNPCsTest {
    static QuestNPCs.NPCInfo npc(int id, String name, String persona) {
        return new QuestNPCs.NPCInfo(id, name, persona);
    }

    static final List<QuestNPCs.NPCInfo> NPCS = List.of(
            npc(1, "Prunesquallor", "prune"),
            npc(2, "§6Lady Chanter", "ladychanter"),
            npc(3, "Sister Agnes", "nun"),
            npc(4, "Sister Bertha", "nun"),
            npc(5, "Bob", null),                // no GeminiNPC trait
            npc(6, "Twin", "twin"),
            npc(7, "Twin", "twin2"),
            npc(8, "Nun", "abbess"));           // an NPC whose name is another NPC's persona

    private static int resolvedId(String name) {
        QuestNPCs.Resolution r = QuestNPCs.resolve(name, NPCS);
        assertNotNull(r.npc(), r.problem());
        return r.npc().id();
    }

    private static String problem(String name) {
        QuestNPCs.Resolution r = QuestNPCs.resolve(name, NPCS);
        assertNull(r.npc());
        return r.problem();
    }

    @Test
    void byName() {
        assertEquals(1, resolvedId("Prunesquallor"));
        assertEquals(5, resolvedId("bob"));
    }

    @Test
    void byNameIgnoresCaseAndColour() {
        assertEquals(2, resolvedId("lady chanter"));
    }

    @Test
    void byUniquePersona() {
        assertEquals(1, resolvedId("prune"));
        assertEquals(2, resolvedId("LadyChanter"));
    }

    @Test
    void nameBeatsPersona() {
        // "nun" is the persona of two NPCs, but one NPC is called "Nun", so that's the one
        assertEquals(8, resolvedId("nun"));
    }

    @Test
    void sharedPersona() {
        List<QuestNPCs.NPCInfo> npcs = List.of(npc(3, "Sister Agnes", "nun"), npc(4, "Sister Bertha", "nun"));
        QuestNPCs.Resolution r = QuestNPCs.resolve("nun", npcs);
        assertNull(r.npc());
        assertEquals("there is no NPC called \"nun\", and 2 NPCs use that persona, so it isn't clear which is meant",
                r.problem());
    }

    @Test
    void sharedName() {
        assertEquals("there are 2 NPCs called \"twin\", so it isn't clear which is meant", problem("twin"));
    }

    @Test
    void missing() {
        assertEquals("there is no NPC called \"helga\", or with that persona", problem("helga"));
    }

    // ---- matches(), which uses the directory ----

    @AfterEach
    void resetDirectory() {
        QuestNPCs.setDirectory(List::of);
    }

    @Test
    void matchesUsesDirectory() {
        QuestNPCs.setDirectory(() -> NPCS);
        assertTrue(QuestNPCs.matches(1, "prune"));
        assertFalse(QuestNPCs.matches(2, "prune"));
        assertFalse(QuestNPCs.matches(3, "sister"));
    }

    @Test
    void matchesFollowsChanges() {
        // a second NPC is given the persona, so it's no longer clear which is meant
        QuestNPCs.setDirectory(() -> List.of(npc(1, "Prunesquallor", "prune")));
        assertTrue(QuestNPCs.matches(1, "prune"));
        QuestNPCs.setDirectory(() -> List.of(npc(1, "Prunesquallor", "prune"), npc(9, "Prune's Twin", "prune")));
        assertFalse(QuestNPCs.matches(1, "prune"));
    }
}
