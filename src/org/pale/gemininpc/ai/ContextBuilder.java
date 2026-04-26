package org.pale.gemininpc.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.*;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.pale.gemininpc.GeminiNPCTrait;
import org.pale.gemininpc.Plugin;
import org.pale.gemininpc.plugininterfaces.Sentinel;
import org.pale.jcfutils.region.Region;
import org.pale.jcfutils.region.RegionManager;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static org.pale.gemininpc.utils.Json.getDifferences;

/**
 * This builds a JSON object - the context-  that is sent with every message to an NPC.
 * It contains information about the world and the NPC state. Only information that
 * has changed since the last request is sent.
 */
public class ContextBuilder {
    private GeminiNPCTrait trait;
    private final NPC npc;
    private final Plugin plugin;

    // distances at which NPCs notice bystander players and NPCs (i.e. data about them
    // is sent to the AI)
    static final double VERY_CLOSE_PLAYERS_DIST = 9;
    static final double VERY_CLOSE_PLAYERS_DISTY = 3;


    public ContextBuilder(GeminiNPCTrait trait){
        this.trait = trait;
        this.npc = trait.getNPC();
        this.plugin = trait.plugin;
    }

    public JsonElement getContext(){
        Location loc = npc.getStoredLocation();
        World w = loc.getWorld();

        Block blk = loc.getBlock();

        byte skyLight = blk.getLightFromSky();
        byte blockLight = blk.getLightFromBlocks();
        byte totalLight = blk.getLightLevel();

        JsonObject root = new JsonObject();

        if (skyLight == 0) {
            root.addProperty("time", plugin.getText("no-skylight-time"));
            root.addProperty("weather", plugin.getText("no-skylight-weather"));
        } else {
            long t = Objects.requireNonNull(w).getTime();
            int hours = (int) ((t / 1000 + 6) % 24);
            int minutes = (int) (60 * (t % 1000) / 1000);
            String timeString = String.format("%02d:%02d", hours, minutes);
            root.addProperty("time", timeString);

            // I need to tell if it's snow or rain.
            // this is a really rough method - it seems pretty impossible to do it properly.

            boolean isSnow = isSnow(loc);

            String weatherString = "clear";
            if (timeString.equals("midnight") || timeString.equals("night"))
                weatherString = "dark";
            else if (timeString.equals("dawn") || timeString.equals("dusk"))
                weatherString = "twilight";

            if (w.isThundering() && w.hasStorm()) {
                weatherString = "stormy and thundering";
            } else if (w.hasStorm()) {
                if (isSnow) {
                    weatherString = "snowing";
                } else {
                    weatherString = "raining";
                }
            }
            root.addProperty("weather", weatherString);
        }

        // add JCFUtils region data
        RegionManager rm = RegionManager.getManager(w);
        if (rm != null) {
            Region region = rm.getSmallestRegion(loc);
            if (region != null) {
                JsonObject regionObj = new JsonObject();
                regionObj.addProperty("name", region.name);
                if(!region.desc.isEmpty()){
                    regionObj.addProperty("description",region.desc);
                }
                root.add("region", regionObj);
            }
        }
        var nearbyWp = trait.waypoints.getNearWaypoint(loc, 100);
        if(nearbyWp!=null){
            if(nearbyWp.distanceSquared() <16){
                root.addProperty("location", nearbyWp.name());
                root.addProperty("location description", nearbyWp.waypoint().desc);
            } else {
                root.addProperty("nearby location", nearbyWp.name());
                root.addProperty("nearby location description", nearbyWp.waypoint().desc);
            }
        }



        // who is nearby?
        if(!trait.getNearbyPlayers().isEmpty()) {
            JsonArray json = new JsonArray();

            var st = trait.getNearbyPlayers().stream()
                    .filter(p -> p.d() < VERY_CLOSE_PLAYERS_DIST
                            && p.dy() < VERY_CLOSE_PLAYERS_DISTY)      // quite close
                    .map(p -> ChatColor.stripColor(p.p().getDisplayName()));
            for(var s : st.toList()){
                json.add(s);
            }
            root.add("nearbyPlayers", json);
        }

        // light conditions?
        if(totalLight>0){
            root.addProperty("light from the sun", String.format("%d/15", skyLight));
            root.addProperty("light from lamps", String.format("%d/15", blockLight));

        } else {
            root.addProperty("light from the sun", "none");
            root.addProperty("light from lamps", "none");
        }

        root.addProperty("world", w.getName());

        // now, add the combat data - extra data will also be added if this is a Sentinel
        appendCombatData(root);
        // and the inventory
        appendInventory(root);

        // we only send the differences!
        JsonObject diffs = getDifferences(prevContext,root);
        prevContext = root;
        return diffs;
    }

    /**
     * Add the inventory as a JSON array to a JsonObject, if we are carrying anything
     */
    private void appendInventory(JsonObject root) {
        JsonArray arr = new JsonArray();
        boolean isempty=true;
        if (npc.getEntity() instanceof Player p) {
            Inventory inv = p.getInventory();
            ItemStack[] items = inv.getContents();
            for (ItemStack item : items) {
                if (item != null) {
                    arr.add(item.getType().name());
                    isempty = false;
                }
            }
        }
        if(!isempty)
            root.add("inventory",arr);
    }



    /**
     * Part of the environment builder - append any combat data to a JsonObject
     */
    private void appendCombatData(JsonObject root) {
        Sentinel.SentinelData d = Plugin.getInstance().sentinelPlugin.makeData(npc);

        if(trait.whenLastDamaged >= 0){
            // was this longer ago than a given duration?
            long lastDamageTime = (System.currentTimeMillis()-trait.whenLastDamaged)/1000;
            if(lastDamageTime > plugin.attackNotificationDuration){
                trait.whenLastDamaged = -1;
                root.addProperty("attacked", String.format("%s has not been attacked recently.",npc.getName()));
            } else {
                root.addProperty("attacked", String.format("%s was recently attacked by %s",
                        npc.getName(), trait.whoDamagedBy.getName()));
            }
        }

        GeminiNPCTrait.MonsterData nm = trait.nearestMonster.get();
        GeminiNPCTrait.MonsterData nvm = trait.nearestVisibleMonster.get();
        if(nvm!=null) {
            root.addProperty("recently seen", nvm.m());
        } else if(nm!=null){
            root.addProperty("recently heard", nm.m());
        } else {
            root.addProperty("recently seen", "no monsters");
            root.addProperty("recently heard", "no monsters");
        }

        if (d != null) {
            // first, how long ago did we see combat
            double t = d.timeSinceAttack / 20.0; // convert to seconds
            trait.log_debug("Time since attack " + t);
            if (t > 60) {
                root.addProperty("combat", String.format("%d minutes ago", (int) t / 60));
            } else if (t > 0) {
                root.addProperty("combat", String.format("%d seconds ago", (int) t));
            } else {
                root.addProperty("combat", plugin.getText("in-combat-now"));
            }
            // now, are we guarding someone?
            if (d.guarding != null)
                root.addProperty("guarding player", d.guarding);
            // health.
            double h = d.health;
            if (h >= 99.0) {
                root.addProperty("health", "maximum");
            } else {
                root.addProperty("health", String.format("%d%%", (int) h));
            }
        }
    }

    JsonObject prevContext = null;

    private static Set<Biome> biomesFromStrings(List<String> s) {
        return s.stream()
                .map(b -> {
                    try {
                        return Registry.BIOME.get(new NamespacedKey(Plugin.getInstance(), b));
                    } catch (IllegalArgumentException e) {
                        Plugin.log("Unknown biome: " + b);
                        return Biome.PLAINS;
                    }
                })
                .collect(Collectors.toSet());
    }

    final Set<Biome> coldBiomes = biomesFromStrings(List.of(
            "FROZEN_OCEAN", "DEEP_FROZEN_OCEAN", "SNOWY_BEACH", "SNOWY_PLAINS",
            "SNOWY_SLOPES", "SNOWY_TAIGA"));

    final Set<Biome> coldAbove100 = biomesFromStrings(List.of(
            "WINDSWEPT_GRAVELLY_HILLS", "WINDSWEPT_HILLS", "WINDSWEPT_FOREST", "STONY_SHORE",
            "DRIPSTONE_CAVES"
    ));

    final Set<Biome> coldAbove160 = biomesFromStrings(List.of(
            "TAIGA", "OLD_GROWTH_SPRUCE_TAIGA"
    ));

    final Set<Biome> coldAbove200 = biomesFromStrings(List.of(
            "OLD_GROWTH_PINE_TAIGA"
    ));

    private boolean isSnow(Location loc) {
        boolean isSnow = false;  // if stormy, is it snow or rain?
        World w = loc.getWorld();
        if (w == null) return false;
        Biome b = w.getBiome(loc);

        // get altitude of npc
        double y = loc.getY();

        if( coldBiomes.contains(b)) {
            isSnow = true; // snow biome
        } else if (coldAbove200.contains(b) && y > 200) {
            isSnow = true; // above 200 in a cold biome
        } else if (coldAbove160.contains(b) && y > 160) {
            isSnow = true; // above 160 in a cold biome
        } else if (coldAbove100.contains(b) && y > 100) {
            isSnow = true; // above 100 in a cold biome
        }
        return isSnow;
    }

}
