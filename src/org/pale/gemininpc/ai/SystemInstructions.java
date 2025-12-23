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
        JsonObject cmds = base.getAsJsonObject("commands_and_descriptions");
        cmds.addProperty(cmd, desc);
        return this;
    }

    public void add(String name, JsonElement elem){
        base.add(name, elem);
    }

    public SystemInstructions(GeminiNPCTrait t){
        base = JsonParser.parseString(t.plugin.getText("standard-system-instructions")).getAsJsonObject();
        base.addProperty("name", t.getNPC().getFullName());
        base.addProperty("gender",t.gender);
        base.addProperty("persona", t.getPersonaString());
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
    }

    public String toString(){
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        return gson.toJson(base);
    }
}
