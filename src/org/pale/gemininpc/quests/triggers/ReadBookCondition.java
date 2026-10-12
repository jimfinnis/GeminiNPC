package org.pale.gemininpc.quests.triggers;

/**
 * True when the player has read a particular book, indicated by its display name
 * (settable with JCFUtils' /jcf dname)
 */
public class ReadBookCondition implements ITriggerCondition {
    final String displayName;

    public ReadBookCondition(String displayName){
        this.displayName = displayName;
    }

    @Override
    public boolean check(TriggerEvent event){
        return event instanceof TriggerEvent.ReadBook(var name)
                && name.equalsIgnoreCase(displayName);
    }
}
