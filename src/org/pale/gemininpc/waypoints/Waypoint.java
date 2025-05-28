package org.pale.gemininpc.waypoints;

import org.bukkit.Location;

import static org.bukkit.Bukkit.getServer;

public class Waypoint {
    final int x, y, z;
    final String world;
    final public String name;

    public String desc;    // description for use by the NPC
    Location loc;   // used to store more detailed positions; generated automatically if not present

    public Waypoint(String world, String name, String desc, int x, int y, int z) {
        this.world = world;
        this.name = name;
        this.desc = desc;
        this.x = x;
        this.y = y;
        this.z = z;
        loc = new Location(getServer().getWorld(world), x + 0.5, y, z + 0.5);
    }

    @Override
    public String toString() {
        return "(" + x + "," + y + "," + z + ") " + desc;
    }

    public void setLocation(Location location) {
        loc = location;
    }
}
