package org.pale.gemininpc.quests.triggers;

import org.bukkit.Material;

/**
 * True when a player gives a particular item to a particular NPC: "give ITEM to NPCNAME".
 */
public class GiveCondition implements ITriggerCondition {
    final Material item;
    final String npcName;

    public GiveCondition(String itemName, String npcName) {
        item = Material.matchMaterial(itemName);
        if(item==null)
            throw new RuntimeException("Unknown item in give condition: "+itemName);
        this.npcName = npcName;
    }

    @Override
    public boolean check(TriggerEvent e) {
        return e instanceof TriggerEvent.Give(var player, var npc, var given)
                && given == item
                && npc.getName().equals(npcName);
    }
}