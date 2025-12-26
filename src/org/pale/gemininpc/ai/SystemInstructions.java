package org.pale.gemininpc.ai;

import com.google.gson.*;
import dev.langchain4j.agent.tool.P;
import org.pale.gemininpc.GeminiNPCTrait;
import org.pale.gemininpc.waypoints.Waypoint;
import org.pale.gemininpc.waypoints.Waypoints;

/**
 * Class for generating the JSON prompt, with both standard parts and non-standard parts.
 */
public class SystemInstructions {
    private JsonObject base;

    public SystemInstructions addCommand(String cmd, String desc){
        JsonObject cmds = base.getAsJsonObject("commands");
        cmds.addProperty(cmd, desc);
        return this;
    }

    public void add(String name, JsonElement elem){
        base.add(name, elem);
    }

    public SystemInstructions(GeminiNPCTrait t){
        base = JsonParser.parseString(t.plugin.getText("standard-system-instructions")).getAsJsonObject();

        if(t.waypoints.getNumberOfWaypoints()>0) {
            addCommand("go WAYPOINT", "go to a given waypoint");
            JsonObject waypoints = new JsonObject();
            base.add("waypoints", waypoints);
            for (String wpn : t.waypoints.getWaypointNames()) {
                try {
                    Waypoint wp = t.waypoints.getWaypoint(wpn);
                    waypoints.addProperty(wpn, wp.desc);
                } catch (Waypoints.Exception e) {
                    throw new RuntimeException(e);
                }
            }
        }

        if(t.isShop())
            add("shop-instructions", t.getShopInstructions());

        if(t.isSentinel()){
            addCommand("guard steve","start guarding the player Steve");
            addCommand("unguard", "stop guarding a player");
        }



        base.addProperty("name", t.getNPC().getFullName());
        base.addProperty("gender",t.gender);

        // folding gender/name data into persona in the hope it gets noticed. But putting the persona
        // at the end.
        String persona = "You are "+t.getNPC().getFullName()+", and are "+t.gender+". "+t.getPersonaString();
        base.addProperty("persona", persona);
    }

    public String toString(){
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        return gson.toJson(base);
    }
}
