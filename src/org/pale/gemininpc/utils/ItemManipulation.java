package org.pale.gemininpc.utils;

import net.citizensnpcs.api.npc.NPC;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.pale.gemininpc.Plugin;

import java.util.HashMap;

public class ItemManipulation {
    public static void giveItemToPlayerOrDrop(NPC npc, Player p, ItemStack st) {
        if (p==null){
            // drop item
            if (npc.getEntity() != null) {
                npc.getEntity().getWorld().dropItemNaturally(npc.getEntity().getLocation(), st);
                Plugin.log("Gave item to player, but player is null. Dropped item instead.");
            } else {
                // if the npc has no entity, we can't drop it
                Plugin.log("Tried to give item to player, but the NPC has no entity.");
            }
        } else {
            // give item to player
            HashMap<Integer, ItemStack> map = p.getInventory().addItem(st);
            if (map.isEmpty()) {
                // we added the item to the player
                Plugin.log("Gave item to player " + p.getName() + ": " + st.getType().name());
                p.sendMessage(ChatColor.AQUA + npc.getFullName() + " gives you " + st.getType().name());
                // if the npc has one, remove it
                if (npc.getEntity() instanceof Player npcp) {
                    Inventory inv = npcp.getInventory();
                    inv.removeItem(st);
                }
            } else {
                Plugin.log("Could not give item to player " + p.getName() + ": " + st.getType().name() + ". Inventory full.");
                p.sendMessage(ChatColor.RED + npc.getFullName() + " tried to give you " + st.getType().name() + " but your inventory is full.");
            }
        }
    }
}
