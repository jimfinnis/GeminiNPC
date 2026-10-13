package org.pale.gemininpc.quests.actions;

import net.citizensnpcs.api.npc.NPC;
import org.bukkit.entity.Player;
import org.pale.gemininpc.quests.Instance;
import org.pale.gemininpc.quests.triggers.TriggerEvent;

/// What an action needs to know when it runs.
/// @param instance the quest instance the action works on
/// @param player the player who caused the action, or whom the NPC was talking to
/// @param npc the NPC involved, or null if there isn't one: for an NPC quest action, the NPC whose LLM
///            chose the action; for a trigger, the NPC in the event (e.g. the one given an item).
///            Actions such as giving an item will need it.
/// @param event the event which fired the trigger, or null if the actions weren't run by a trigger
///              (e.g. an NPC quest action or `on-complete`). Actions such as telling nearby NPCs what
///              happened will need it.
public record ActionContext(Instance instance, Player player, NPC npc, TriggerEvent event) {
}
