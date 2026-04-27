package org.pale.gemininpc.utils;

import io.marioslab.basis.template.TemplateContext;
import net.citizensnpcs.api.npc.NPC;
import org.pale.gemininpc.GeminiNPCTrait;
import org.pale.gemininpc.Plugin;
import org.pale.gemininpc.actions.Action;
import org.pale.gemininpc.actions.ActionRegistry;
import org.pale.gemininpc.ai.Persona;

import java.util.*;

/**
 * Useful functions for templates wrapped in a class.
 * This is created when the NPC is created or reset, but the functions are added to the template context
 * every time we need to template a string.
 * Each NPC has their own, so we can keep NPC-private state here.
 */
public class TemplateFunctions {
    GeminiNPCTrait trait = null;
    Random prng;
    ;
    // map of private values for this NPC accessed with the get/set functions.
    public Map<String,Object> npcMap = new HashMap<>();

    public TemplateFunctions(GeminiNPCTrait trait) {
        this.trait = trait;
        // by default, the PRNG is seeded by the hashcode of the NPC name.
        // This can be overriden by "setseed" in a template, if you really don't like the name.
        prng = new Random(trait.seedString.hashCode());

        // add initial values to npc map (well, really just replace with dupe).
        Persona p = Plugin.getInstance().personae.get(trait.personaName);
        if(p!=null){
            npcMap = new HashMap<>(p.npcMapInitialValues);
        }
    }

    // function that takes a List<Object> and returns a string
    @FunctionalInterface
    public interface ListStringFunction {
        @SuppressWarnings("unused")
        String apply(List<Object> arg);
    }
    // takes a list,int,string and returns a string
    @FunctionalInterface
    public interface ListObjectIntStringFunction {
        @SuppressWarnings("unused")
        String apply(List<Object> arg1, int arg2, String s);
    }

    // annoyingly it seems it really needs the method to be called "apply", so we can't use
    // the standard java.util.function.Function interface. So we have to define our own.
    @FunctionalInterface
    public interface IntIntToIntFunction {
        @SuppressWarnings("unused")
        int apply(int arg1, int arg2);
    }

    @FunctionalInterface
    public interface StringToStringFunction {
        String apply(String arg);
    }

    @FunctionalInterface
    public interface StringStringToStringFunction {
        String apply(String a1, String a2);
    }

    @FunctionalInterface
    public interface StringStringStringToStringFunction {
        String apply(String a1, String a2, String a3);
    }

    @FunctionalInterface
    public interface StringToObjectFunction {
        Object apply(String arg);
    }

    @FunctionalInterface
    public interface StringStringToObjectFunction {
        Object apply(String a1, String a2);
    }


    @FunctionalInterface
    public interface StringObjectToStringFunction {
        String apply(String a1, Object a2);
    }

    @FunctionalInterface
    public interface StringStringObjectToStringFunction {
        String apply(String a1, String a2, Object a3);
    }


    /**
     * Add functions to the template context. This is run every time the template is used!
     * @param tc the template context to add to
     */
    public void addFunctions(TemplateContext tc){

        tc.set("choose", stringChooseFunction);
        tc.set("pick", pickFunction);
        tc.set("random", randomFunction);
        tc.set("drop", dropFunction);   // replaces any value with nothing; useful for list.add() etc.
        tc.set("mapset", addToMapFunction);  // set an item in a GLOBAL map, creating a new map if needed. Args: mapname,k,v
        tc.set("map", getMapFunction); // get a GLOBAL map by name
        tc.set("listadd", addToListFunction); // append an item to a list, creating a new list if needed. Args: listname,v
        tc.set("list", getListFunction); // get a list by name

        tc.set("set", setNPCMapFunction); // (k,v) set a value in this NPC's private map
        tc.set("get", getNPCMapFunction); // (v) get a value in this NPC's private map

        tc.set("setother", setOtherNPCMapFunction); // (npcname, k, v) set a value in another NPC's private map
        tc.set("getother", getOtherNPCMapFunction); // (npcname, k) get a value from another NPC's private map

        tc.set("has", hasFunction); // (matname) returns true if this minecraft mat is in the inventory
        tc.set("at", isAtFunction); // (name) returns true if the NPC is at the given waypoint it knows about or is in the given region

        // add actions for use in an "actions" dict. Argument is an "action group" name e.g. "default" or "sentinel",
        // and adds data to the "actions" map which you can then get with "map".
        tc.set("actions", actionsFunction);

        // we also add some helpful stuff from Java
        tc.set("String",String.class);  // e.g. this lets us use String.join etc.
    }

    private String chooseItem(Object item, boolean remove) {
        if(item instanceof List<?> sublist){
            // if the item is a list, pick one random item from it
            int idx;
            if(sublist.isEmpty()) return "";
            if(sublist.size() == 1)
                idx = 0;
            else
                idx = prng.nextInt(sublist.size());
            // get and perhaps remove the item
            Object subitem = sublist.get(idx);
            if(remove)
                sublist.remove(idx);
            // recursively call chooseItem on the item so nested lists work
            return chooseItem(subitem, remove);
        } else {
            // otherwise just return the item
            return item.toString();
        }
    }

    /**
     * Choose a random string from a list of strings using the prng object
     */
    private final ListStringFunction stringChooseFunction = (args) -> {
        return chooseItem(args, false); // no need to remove, this is a single choice.
    };

    /**
     * Given a list, a count and a delimiter string, pick that many random elements from the list.
     * They will all be different, because we remove them from the list as we go (we work from a copy).
     * If the item is itself a list, one random item will be returned and the parent list will be removed.
     * This lets us have mutually exclusive items - e.g. "a sword" or "a bow" but not both - by putting
     * "sword" and "bow" in a sublist.
     *
     */
    private final ListObjectIntStringFunction pickFunction = (args, count, s) -> {

        StringBuilder sb = new StringBuilder();
        args = new ArrayList<>(args); // make a copy of the list
        for (int i = 0; i < count; i++) {
            sb.append(chooseItem(args, true)); // choose and remove the item from the list
            if (i < count - 1) {
                sb.append(s); // add the delimiter
            }
        }
        return sb.toString();
    };

    /**
     * Given two ints return random.nextint(a,b) - a is inclusive, b is exclusive.
     */

    private final IntIntToIntFunction randomFunction = (a, b) -> prng.nextInt(a, b);

    private final StringToStringFunction dropFunction = (a) -> "";

    Map<String, Map<String,String>> maps = new HashMap<>();
    Map<String, List<String>> lists = new HashMap<>();

    private final StringStringStringToStringFunction addToMapFunction = (mapname, key, value) -> {
        Map<String, String> map = maps.computeIfAbsent(mapname,k -> new HashMap<>());
        map.put(key,value);
        return "";
    };
    private final StringToObjectFunction getMapFunction = mapname -> maps.computeIfAbsent(mapname, k -> new HashMap<>());

    private final StringStringToStringFunction addToListFunction = (listname, value) -> {
        List<String> lst = lists.computeIfAbsent(listname,k -> new ArrayList<>());
        lst.add(value);
        return "";
    };

    private final StringToObjectFunction getListFunction = listname -> lists.computeIfAbsent(listname, k-> new ArrayList<>());

    private final StringToStringFunction actionsFunction = (groupname) -> {
        Map<String, String> map = maps.computeIfAbsent("actions",k -> new HashMap<>());
        for(Map.Entry<String, ActionRegistry.Entry> e: trait.plugin.actionRegistry.getMap(groupname).entrySet() ){
            Plugin.getInstance().getLogger().info("  adding action "+e.getKey()+" from group "+groupname);
            Action a = e.getValue().act();
            // we need to output the USAGE, not just the name.
            map.put(a.usage(), a.desc());
        }
        return "";
    };

    private final StringObjectToStringFunction setNPCMapFunction = (String key, Object value) -> {
        npcMap.put(key, value);
        trait.plugin.getLogger().info("Setting NPC map "+key+"="+value);
        return "";
    };

    private final StringToObjectFunction getNPCMapFunction = (String key)
            -> npcMap.getOrDefault(key, "");

    private final StringStringObjectToStringFunction setOtherNPCMapFunction = (String npcName, String k, Object v) -> {
        NPC npc = Plugin.getInstance().getChatNPCByName(npcName);
        if(npc != null){
            GeminiNPCTrait trait = Plugin.getTraitFor(npc);
            trait.getTemplateFunctions().npcMap.put(k,v);
            trait.plugin.getLogger().info("Setting "+npcName+"'s map "+k+"="+v);
        }
        return "";
    };

    private final StringStringToObjectFunction getOtherNPCMapFunction = (String npcName, String k) -> {
        NPC npc = Plugin.getInstance().getChatNPCByName(npcName);
        if(npc != null){
            GeminiNPCTrait trait = Plugin.getTraitFor(npc);
            return trait.getTemplateFunctions().npcMap.get(k);
        }
        return "";
    };

    private final StringToObjectFunction hasFunction = (String matName) -> trait.has(matName);

    private final StringToObjectFunction isAtFunction = (String wpname) -> trait.isAtWaypointOrInRegion(wpname);
}
