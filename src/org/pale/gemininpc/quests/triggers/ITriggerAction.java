package org.pale.gemininpc.quests.triggers;

import org.pale.gemininpc.quests.Instance;

/**
 * An action that results from a TriggerCondition being true in a Trigger
 */
public interface ITriggerAction {
    void run(Instance inst);
}
