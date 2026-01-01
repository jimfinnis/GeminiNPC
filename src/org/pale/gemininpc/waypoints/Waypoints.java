package org.pale.gemininpc.waypoints;

import net.citizensnpcs.api.ai.Navigator;
import net.citizensnpcs.api.util.DataKey;
import org.bukkit.Location;
import org.pale.gemininpc.GeminiNPCTrait;
import org.pale.gemininpc.Plugin;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.bukkit.Bukkit.getServer;

public class Waypoints {

    public static class Exception extends java.lang.Exception {
        public Exception(String msg){
            super(msg);
        }
    }

    final Map<String, Waypoint> waypoints = new HashMap<>();

    public void add(String name, String world, String desc, int x, int y, int z) {
        Waypoint w = new Waypoint(world, name, desc, x, y, z);
        waypoints.put(name.toLowerCase(), w);
    }

    public void add(String name, String desc, Location loc) {
        if(loc.getWorld()==null)
            return;
        add(name, loc.getWorld().getName(), desc, loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    public void delete(String name) throws Exception {
        if(!waypoints.containsKey(name))
            throw new Exception("Waypoint not found: " + name);
        waypoints.remove(name);
    }

    public Waypoint getWaypoint(String name) throws Exception {
        Waypoint w = waypoints.get(name);
        if(w==null)throw new Exception("No such waypoint " + name);
        return w;
    }

    public Set<String> getWaypointNames(){
        return waypoints.keySet();
    }

    public int getNumberOfWaypoints(){
        return waypoints.size();
    }

    public void save(DataKey root){
        root.removeKey("waypoints");
        int i =0;
        for(String s: waypoints.keySet()){
            Waypoint w = waypoints.get(s);
            root.setString("waypoints."+i+".name", s);
            root.setInt("waypoints."+i+".x", w.x);
            root.setInt("waypoints."+i+".y", w.y);
            root.setInt("waypoints."+i+".z", w.z);
            root.setString("waypoints."+i+".desc", w.desc);
            i++;
        }
    }

    public void load(DataKey root){
        waypoints.clear();
        if(!root.keyExists("waypoints")) return;
        for(DataKey key: root.getRelative("waypoints").getIntegerSubKeys()){
            String name = key.getString("name");
            int x = key.getInt("x");
            int y = key.getInt("y");
            int z = key.getInt("z");
            String desc = key.getString("desc");
            String world;
            if(key.keyExists("world")){
                world = key.getString("world");
            } else {
                world = getServer().getWorlds().get(0).getName();
            }
            add(name, world, desc, x, y, z);
        }
    }

    public Location pathTo(GeminiNPCTrait t, String name) throws Exception {
        Waypoint w = waypoints.get(name.toLowerCase());
        // wonder what this does in a different world???
        if(w==null)throw new Exception("No such waypoint " + name);
        Navigator navigator = t.getNPC().getNavigator();
        final Location loc = new Location(t.getNPC().getEntity().getWorld(), w.x+0.5, w.y, w.z+0.5);
        var p = navigator.getLocalParameters();
        // p.range((float)loc.distance(t.getNPC().getEntity().getLocation())+5.0f);
        p.range(100.0f);

        if(!navigator.canNavigateTo(loc)){
            Plugin.log("Cannot navigate to waypoint "+name+" at "+loc+" from "+t.getNPC().getEntity().getLocation());
        }

        navigator.setTarget(loc);
        Plugin.log("Target set - "+t.getNPC().getFullName()+" to waypoint "+name+" at "+loc);
        /*
        p.stuckAction( (npc1, navigator) -> {
            Plugin.log("STUCK. Teleporting "+npc1.getFullName()+" to waypoint "+name+" at "+loc);
            navigator.cancelNavigation();
            // if we are stuck, we should just teleport to the waypoint
            t.getNPC().teleport(loc.add(0,1,0), PlayerTeleportEvent.TeleportCause.PLUGIN);
            return true;
        });

         */
        return loc;
    }

    public record NearWaypointResult(String name, double distanceSquared, Waypoint waypoint) {
    }

    public NearWaypointResult getNearWaypoint(Location loc,double thresholdSquared) {
        double min = 1000000;
        Waypoint near = null;
        for(String s: waypoints.keySet()) {
            Waypoint w = waypoints.get(s);
            if(w.loc.getWorld() == loc.getWorld()) { // only consider waypoints in the same world
                double d = loc.distanceSquared(w.loc);
                if (d < min && d < thresholdSquared) {
                    min = d;
                    near = w;
                }
            }
        }
        if(near!=null){
            return new NearWaypointResult(near.name, min, near);
        } else {
            return null;
        }
    }

}
