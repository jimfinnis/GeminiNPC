package org.pale.gemininpc.quests.triggers;

/**
 * An event that triggers a Trigger.
 */
public interface ITriggerCondition {
    boolean check(TriggerEvent e);
}
