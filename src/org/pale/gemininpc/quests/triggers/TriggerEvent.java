package org.pale.gemininpc.quests.triggers;

import net.citizensnpcs.api.npc.NPC;
import org.bukkit.inventory.ItemStack;

/**
 * Something that has happened in the world which might fire a Trigger. Each kind of event is a record
 * implementing this interface, carrying its own arguments. Conditions pick out the events they care about
 * with pattern matching, e.g.
 *     if (e instanceof TriggerEvent.Give(var npc, var item)) ...
 *
 * We check the player in the Trigger code.
 *
 * To add a new event type, add a record here.
 */
public interface TriggerEvent {
    /**
     * A player has given an item to an NPC.
     */
    record Give(NPC npc, ItemStack item) implements TriggerEvent {}
}