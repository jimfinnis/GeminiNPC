package org.pale.gemininpc.quests.triggers;

import net.citizensnpcs.api.npc.NPC;
import org.bukkit.ChatColor;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

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
    /// The NPC involved in the event, if there is one. Records with an `npc` component provide this
    /// automatically, because the record's accessor overrides it.
    default NPC npc() {
        return null;
    }

    /// A short description for the log, to follow the player's name, e.g. `gave "Ominous Banner" to
    /// "Lady Chanter"`. Names are in quotes so that stray characters in them (such as quotes added by
    /// `/jcf dname`) show up.
    String describe();

    /// How an item is named in a description: its display name if it has one, otherwise its material.
    private static String itemName(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName())
            return "\"" + ChatColor.stripColor(meta.getDisplayName()) + "\"";
        return item.getType().name().toLowerCase().replace('_', ' ');
    }

    /**
     * A player has given an item to an NPC.
     */
    record Give(NPC npc, ItemStack item) implements TriggerEvent {
        public String describe() {
            return "gave " + itemName(item) + " to \"" + ChatColor.stripColor(npc.getName()) + "\"";
        }
    }

    /**
     * A player has read a book
     * @param displayName - the book's display name, which you can set with /jcf dname; null if it has none
     * @param title - the title given when a written book was signed; null for an unsigned book and quill
     */
    record ReadBook(String displayName, String title) implements TriggerEvent {
        public String describe() {
            if (displayName == null && title == null)
                return "read a book with no name or title";
            String s = "read";
            if (displayName != null)
                s += " \"" + ChatColor.stripColor(displayName) + "\"";
            if (title != null)
                s += " (title \"" + ChatColor.stripColor(title) + "\")";
            return s;
        }
    }
}
