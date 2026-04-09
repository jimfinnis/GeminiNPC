package org.pale.gemininpc.actions;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.pale.gemininpc.GeminiNPCTrait;
import org.pale.gemininpc.Plugin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;

import static org.pale.gemininpc.commands.CommandRegistry.sortedMethods;

/**
 * Registry of actions
 */
public class ActionRegistry  {

    public record Entry(String name, Object o, Method m, Action act) {};

    // map of String->Entry for each group
    private final Map<String, TreeMap<String,Entry>> registry = new TreeMap<>();

    // map of String->Entry for all actions, used in handling actions.
    private final Map<String, Entry> allActions = new HashMap<>();

    /**
     * Get the map for a given action group
     * @param groupname name of the group
     * @return map
     */
    public Map<String,Entry> getMap(String groupname){
        return registry.computeIfAbsent(groupname, k->new TreeMap<String,Entry>());
    }

    /**
     * Register all @Action annotated methods in the provided class
     * @param handler the container for the methods
     */
    public void register(Object handler){
        for(Method m : sortedMethods(handler)){
            Action act = m.getAnnotation(Action.class);
            if(act!=null){
                Class<?>[] params = m.getParameterTypes();
                if(params.length != 1 || !params[0].equals(ActionInfo.class)){
                    Plugin.warn("Error in @Action on method "+m.getName()+": parameter must be one CallInfo");
                } else {
                    // get the action's name field; if there isn't one use the method name
                    String name = act.name();
                    if(name.isEmpty())name = m.getName();
                    // get the map for the action's group, creating one if required
                    TreeMap<String,Entry> groupmap = registry.computeIfAbsent(act.group(), k->new TreeMap<>());
                    // and put the action into it.
                    Entry e = new Entry(name, handler, m, act);
                    groupmap.put(name, e);
                    // also add it to all actions
                    allActions.put(name,e);
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
        Entry e = allActions.getOrDefault(act,null);
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
