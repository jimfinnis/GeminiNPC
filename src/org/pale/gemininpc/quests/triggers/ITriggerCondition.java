package org.pale.gemininpc.quests.triggers;

import java.util.Collection;
import java.util.List;

/**
 * An event that triggers a Trigger.
 */
public interface ITriggerCondition {
    boolean check(TriggerEvent e);

    /// Names of NPCs the condition refers to, so they can be checked against the NPCs which exist.
    default Collection<String> npcNames() {
        return List.of();
    }
}
