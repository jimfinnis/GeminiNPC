package org.pale.gemininpc.quests.triggers;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * True when a player gives a particular item to a particular NPC: "give ITEM to NPCNAME".
 */
public class GiveCondition implements ITriggerCondition {
    // item is either a Material name (case-independent), an ItemMeta item name, or an ItemMeta display name.
    // If it's a material we just check against the material, otherwise if either the itemname or displayname
    // match then the check is true.

    final String itemName;
    final Material material; // the material named by itemName, or null if it isn't a material name
    final String npcName;   // the name of the NPC to whom you gave the item

    public GiveCondition(String itemName, String npcName) {
        this.itemName = itemName;
        this.npcName = npcName;
        // matchMaterial ignores case and turns spaces into underscores, so "white banner" is WHITE_BANNER
        material = Material.matchMaterial(itemName);
    }

    private boolean itemMatches(ItemStack item) {
        if (material != null) {  // easy check
            return item.getType() == material;
        }
        // otherwise we have to do name comparisons
        ItemMeta meta = item.getItemMeta();
        if(meta == null)
            return false;   // we have no metadata to compare further

        // now we check against the display name and the item name, ignoring colour codes
        if (meta.hasDisplayName() && namesMatch(meta.getDisplayName()))
            return true;
        else return meta.hasItemName() && namesMatch(meta.getItemName());
    }

    /// Compare a name from an item with the one in the quest, ignoring case and colour codes, so that a
    /// name set with e.g. `/jcf dname &6Ominous Banner` still matches "ominous banner".
    private boolean namesMatch(String name) {
        return ChatColor.stripColor(itemName).equalsIgnoreCase(ChatColor.stripColor(name));
    }

    @Override
    public boolean check(TriggerEvent event) {
        return event instanceof TriggerEvent.Give(var npc, var given)
                && itemMatches(given)
                && npc.getName().equals(npcName);
    }
}
