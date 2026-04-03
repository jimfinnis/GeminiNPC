package org.pale.gemininpc.actions;

import org.bukkit.entity.Player;
import org.pale.gemininpc.GeminiNPCTrait;
import org.pale.gemininpc.Plugin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static org.pale.gemininpc.commands.CommandRegistry.sortedMethods;

/**
 * Registry of actions
 */
public class ActionRegistry {
    private record Entry(String name, Object o, Method m) {};

    private final Map<String, Entry> registry = new HashMap<>();

    public void register(Object handler){
        for(Method m : sortedMethods(handler)){
            Action cmd = m.getAnnotation(Action.class);
            if(cmd!=null){
                Class<?>[] params = m.getParameterTypes();
                if(params.length != 1 || !params[0].equals(ActionInfo.class)){
                    Plugin.warn("Error in @Action on method "+m.getName()+": parameter must be one CallInfo");
                } else {
                    String name = cmd.name();
                    if(name.isEmpty())name = m.getName();
                    registry.put(name, new Entry(name, handler, m));
                }
            }
        }
    }

    public void handleAction(GeminiNPCTrait t, Player target, String action){
        // extract the first word from the action string
        var out = action.split("\\s+", 2);
        String npcname = t.getNPC().getName();
        String act = out[0].trim();
        if(act.isEmpty())
            return;
        String args = out.length<2 ? "" : out[1];
        Entry e = registry.getOrDefault(act,null);
        var logger = Plugin.getInstance().getLogger();
        if(e == null){
            logger.info("Unknown action "+act+" from NPC "+npcname);
        } else {
            // invoke the action
            try {
                logger.info("Attempting action "+act);
                e.m.invoke(e.o, new ActionInfo(t, args, target));
            } catch(IllegalAccessException | IllegalArgumentException | InvocationTargetException x){
                Plugin.getInstance().getLogger().info("Exception in action "+act+" from NPC "+npcname);
                //noinspection CallToPrintStackTrace
                x.printStackTrace();
            }
        }
    }

}
