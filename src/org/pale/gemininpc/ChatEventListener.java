package org.pale.gemininpc;

import net.citizensnpcs.api.ai.event.NavigationCancelEvent;
import net.citizensnpcs.api.ai.event.NavigationCompleteEvent;
import net.citizensnpcs.api.ai.event.NavigationEvent;
import net.citizensnpcs.api.ai.event.NavigationStuckEvent;
import net.citizensnpcs.api.event.NPCRightClickEvent;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * This listens for the various events we're interested in, not just the chat - despite
 * the name (this class was written when the only event was Chat, but I don't want to rename
 * it because reasons).
 * <p>
 * IT DOES NOT listen for NPC specific events - they are in the trait.
 *
 * @author white
 */
public final class ChatEventListener implements Listener {
    Plugin plugin;

    public ChatEventListener(Plugin p) {
        plugin = p;
        p.getServer().getPluginManager().registerEvents(this, p);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void monitorChat(final AsyncPlayerChatEvent e) {
        if (e.isAsynchronous()) {
            // might be better do do this in thread...
            plugin.getServer().getScheduler().scheduleSyncDelayedTask(plugin,
                    () -> plugin.handleMessage(e.getPlayer(), e.getMessage()));
        } else {
            plugin.handleMessage(e.getPlayer(), e.getMessage());
        }
    }

    /**
     * This is the event handler for when a player right clicks on an NPC.
     *
     * @param event
     */
    @EventHandler
    public void click(NPCRightClickEvent event) {
        NPC npc = event.getNPC();
        GeminiNPCTrait t = plugin.getTraitFor(npc);
        if (t == null) {
            return;
        }
        Plugin.log("click event on " + npc.getFullName() + "from player " + event.getClicker().getDisplayName());
        if (event.getNPC() == npc) {
            t.give(event.getClicker()); // give the item to the NPC
        }
    }

    // it looks like EntityDeathEvent doesn't give the killer if it's one
    // of our NPCs, so I'm going to try to track the last damager. It might
    // be possible in more recent versions of Bukkit using getDamageSource.

    // I'll also track who each NPC damaged and when
    Map<Entity, GeminiNPCTrait> damageMap = new HashMap<>();    // for a given mob, who damaged it?

    @EventHandler
    public void onEntityDamage(EntityDamageByEntityEvent event){
        Plugin.log(ChatColor.RED+"Entity damage event: " + event.getEntity().getName());
        Entity attacker = event.getDamager();
        Entity defender = event.getEntity();
        Plugin.log("Attacker: " + attacker.getName()+" is a "+attacker.getType().name());
        // if an entity is shot, the attacker is actually the arrow - change that to the entity
        // that fired it.
        if(attacker instanceof Arrow){
            Arrow arrow = (Arrow) attacker;
            Plugin.log("Arrow shooter: " + attacker.getName()+" is a "+attacker.getType().name());
            if(arrow.getShooter() instanceof Entity){
                attacker = (Entity) arrow.getShooter();
            }
        }
        // if the attacker is a player, we need to check if it's one of our NPCs and note it.
        if(attacker instanceof Player) {
            GeminiNPCTrait t = plugin.getTraitFor(attacker);
            if (t != null) {
                Plugin.log("NPC " + t.getNPC().getFullName() + " damaged " + defender.getName());
                // at this point we know that one of our NPCs attacked. We can assume that it will be
                // responsible for any subsequent death.
                damageMap.put(defender, t);
            }
        }
        // if the attacker is a mob, is the defender one of our NPCs?
        if(defender instanceof Player) {
            GeminiNPCTrait t = plugin.getTraitFor(defender);
            if (t != null) {
                Plugin.log("NPC " + t.getNPC().getFullName() + " damaged by " + attacker.getName());
                // at this point we know that one of our NPCs was attacked.
                t.onDamagedEntity(attacker);
            }
        }
    }

    /**
     * This is a handler for when an entity dies - we want to see if we were responsible and respond accordingly.
     * @param event
     */

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event){
        Plugin.log(ChatColor.RED+"Entity death event: " + event.getEntity().getName());
        Plugin.log("Attacker: " + event.getEntity().getKiller());
        Entity e = event.getEntity();
        if(damageMap.containsKey(e)){
            // we know that one of our NPCs attacked. We can assume that it will be
            // responsible for any subsequent death.
            GeminiNPCTrait t = damageMap.get(e);
            Plugin.log("NPC " + t.getNPC().getFullName() + " killed " + e.getName());
            t.onKill(event.getEntity().getName());
        }
    }

    @EventHandler
    public void navCancelled(NavigationCancelEvent e){
        NPC npc = e.getNPC();
        GeminiNPCTrait t = plugin.getTraitFor(npc);
        if(t != null) {
            t.navComplete(GeminiNPCTrait.NavCompletionCode.CANCELLED);
        }
    }

    @EventHandler
    public void navEnded(NavigationCompleteEvent e){
        NPC npc = e.getNPC();
        GeminiNPCTrait t = plugin.getTraitFor(npc);
        if(t != null) {
            t.navComplete(GeminiNPCTrait.NavCompletionCode.ARRIVED);
        }
    }
}