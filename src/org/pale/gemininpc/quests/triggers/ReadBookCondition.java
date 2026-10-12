package org.pale.gemininpc.quests.triggers;

import org.bukkit.ChatColor;

/**
 * True when the player has read a particular book, indicated by either its display name
 * (settable with JCFUtils' /jcf dname) or the title it was signed with.
 */
public class ReadBookCondition implements ITriggerCondition {
    final String name;

    public ReadBookCondition(String name){
        this.name = ChatColor.stripColor(name);
    }

    @Override
    public boolean check(TriggerEvent event){
        return event instanceof TriggerEvent.ReadBook(var displayName, var title)
                && (namesMatch(displayName) || namesMatch(title));
    }

    /// Case and colour codes are ignored, so a name set with e.g. `/jcf dname &6Torn Journal` still matches.
    /// The comparison is this way round because the book's name may be null.
    private boolean namesMatch(String bookName){
        return name.equalsIgnoreCase(ChatColor.stripColor(bookName));
    }
}
