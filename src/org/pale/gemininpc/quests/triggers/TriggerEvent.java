package org.pale.gemininpc.quests.triggers;

import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * Something that has happened in the world which might fire a Trigger. Each kind of event is a record
 * implementing this interface, carrying its own arguments. Conditions pick out the events they care about
 * with pattern matching, e.g.
 *     if (e instanceof TriggerEvent.Give(var player, var npc, var item)) ...
 *
 * To add a new event type, add a record here.
 */
public interface TriggerEvent {
    /**
     * The player who caused the event - used to find the per-player quest state.
     */
    Player player();

    /**
     * A player has given an item to an NPC.
     */
    record Give(Player player, NPC npc, Material item) implements TriggerEvent {}
}