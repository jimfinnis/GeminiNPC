package org.pale.gemininpc.quests.actions;

import org.bukkit.entity.Player;
import org.pale.gemininpc.quests.Instance;
import org.pale.gemininpc.quests.triggers.TriggerEvent;

/// What an action needs to know when it runs.
/// @param instance the quest instance the action works on
/// @param player the player who caused the action
/// @param event the event which fired the trigger, or null if the actions weren't run by a trigger
///              (e.g. `on-complete`). Actions such as telling nearby NPCs what happened will need it.
public record ActionContext(Instance instance, Player player, TriggerEvent event) {
}
