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

    /**
     * A player has read a book
     * @param displayName - the book's display name, which you can set with /jcf dname; null if it has none
     * @param title - the title given when a written book was signed; null for an unsigned book and quill
     */
    record ReadBook(String displayName, String title) implements TriggerEvent {}
}