package org.pale.gemininpc.actions;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.pale.gemininpc.GeminiNPCTrait;
import org.pale.gemininpc.Plugin;
import org.pale.gemininpc.plugininterfaces.Sentinel;
import org.pale.gemininpc.utils.ItemManipulation;
import org.pale.gemininpc.utils.TextUtils;
import org.pale.gemininpc.waypoints.Waypoints;

/**
 * Contains all the actions that can be performed by the LLM as the result of an "action" response
 */
public class Actions {
    Plugin plugin;

    public Actions(Plugin plugin){
        this.plugin = plugin;
    }

    @Action
    public void give(ActionInfo a){
        String mname = a.args().toUpperCase().trim().replaceAll(" ", "_");
        Material mat = Material.getMaterial(mname);
        if (mat != null) {
            ItemManipulation.giveItemToPlayerOrDrop(a.npc(), a.target(), new ItemStack(mat, 1));
        } else {
            a.trait().log_debug("Bad material name in action: " + mname);
        }
    }

    @Action
    public void setguard(ActionInfo a){
        if(a.trait().isSentinel()){
            Sentinel s = plugin.sentinelPlugin;
            String name = a.args().trim();
            if(name.equalsIgnoreCase("none")){
                s.setGuard(a.npc(), null);
                Plugin.log("NPC " + a.npc().getFullName() + " unguarded.");
            } else {
                Player p2 = plugin.getServer().getPlayer(name);
                if(p2 != null) {
                    s.setGuard(a.npc(), p2.getUniqueId());
                    Plugin.log("NPC " + a.npc().getFullName() + " is now guarding " + p2.getDisplayName());
                } else {
                    Plugin.log("Cannot find player to guard: " + name);
                }
            }
        } else {
            Plugin.log("NPC " + a.npc().getFullName() + " does not have Sentinel trait.");
        }
    }

    @Action
    public void unguard(ActionInfo a){
        if(a.trait().isSentinel()){
            plugin.sentinelPlugin.setGuard(a.npc(), null);
            Plugin.log("NPC " + a.npc().getFullName() + " unguarded.");
        } else {
            Plugin.log("NPC " + a.npc().getFullName() + " does not have Sentinel.");
        }
    }

    @Action
    public void go(ActionInfo a){
        String name = a.args().trim();
        if(name.equalsIgnoreCase("none")){
            a.npc().getNavigator().cancelNavigation();
            Plugin.log("NPC " + a.npc().getFullName() + " got a 'go none'.");
        } else {
            try {
                a.trait().pathTo(name);
                Plugin.log("NPC " + a.npc().getFullName() + " is now going to waypoint "+name);
            } catch(Waypoints.Exception e) {
                Plugin.log("Cannot find waypoint: " + name);
            }
        }
    }

    @Action
    public void writebook(ActionInfo a){
        String bookdata = a.args().trim();
        // split into title and text by vertical bar
        String[] parts = bookdata.split("\\|", 2);
        if(parts.length < 2){
            parts = new String[2];
            parts[0] = "Untitled";
            parts[1] = bookdata;
        }
        String title = parts[0].trim();
        String text = parts[1].trim();
        ItemStack book = TextUtils.writeBook(title, a.npc().getFullName(), text);
        // give the book to the player if there is one. Drop it on the ground if there isn't,
        // or if the player had no inventory room.
        Plugin.log("Book written");
        ItemManipulation.giveItemToPlayerOrDrop(a.npc(),a.target(), book);


    }

}
