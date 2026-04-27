package org.pale.gemininpc.actions;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
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

    @Action(usage="give ITEM", desc="Given an item to a player. ITEM must be a Minecraft material. Never give a WRITTEN_BOOK with this command.")
    public void give(ActionInfo a){
        String mname = a.args().toUpperCase().trim().replaceAll(" ", "_");
        Material mat = Material.getMaterial(mname);
        if (mat != null) {
            ItemManipulation.giveItemToPlayerOrDrop(a.npc(), a.target(), new ItemStack(mat, 1));
        } else {
            a.trait().log_debug("Bad material name in action: " + mname);
        }
    }

    @Action(usage="setguard PLAYER", desc="Start guarding the specified player.", group="sentinel")
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

    @Action(usage="unguard", desc="Stop guarding the player you are currently guarding.", group="sentinel")
    public void unguard(ActionInfo a){
        if(a.trait().isSentinel()){
            plugin.sentinelPlugin.setGuard(a.npc(), null);
            Plugin.log("NPC " + a.npc().getFullName() + " unguarded.");
        } else {
            Plugin.log("NPC " + a.npc().getFullName() + " does not have Sentinel.");
        }
    }

    @Action(usage="go WAYPOINT", desc="Go to a known waypoint. WAYPOINT must be a waypoint you know about.", group="waypoints")
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

    @Action(usage="writebook TITLE|TEXT",
    desc="Write a book and give it to the player. TITLE should be a " +
            "few words. TEXT can be as long as you can manage. Write in a more formal " +
            "way than you speak. Make sure the text is appropriate for the text of a book. " +
            "\"TITLE\" in the action should be replaced by the title, and \"TEXT\" by the text. " +
            "Write books rarely or when requested to do so.",
            group="writer"
    )
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

    @Action(usage="set KEY=VALUE",
    desc="Set a value inside your private memory. Only do this when your current-state commands it")
    public void set(ActionInfo a){
        String args = a.args().trim();
        String[] parts = args.split("=", 2);
        String key = parts[0].trim();
        String value = parts[1].trim();
        Plugin.log("NPC "+a.npc().getName()+" setting private value "+key+" to "+value);
        a.trait().getTemplateFunctions().npcMap.put(key,value);
    }
}
