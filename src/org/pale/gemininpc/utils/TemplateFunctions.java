package org.pale.gemininpc.utils;

import io.marioslab.basis.template.TemplateContext;
import org.checkerframework.checker.units.qual.A;
import org.pale.gemininpc.GeminiNPCTrait;

import java.util.*;

/**
 * Useful functions for templates wrapped in a class.
 */
public class TemplateFunctions {
    final GeminiNPCTrait trait;
    Random prng;
    
    public TemplateFunctions(GeminiNPCTrait trait) {
        this.trait = trait;
        prng = new Random(trait.getNPC().getName().hashCode());
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
    public interface StringStringFunction {
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
    public interface StringObjectFunction {
        Object apply(String arg);
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
        tc.set("mapset", addToMapFunction);  // set an item in a map, creating a new map if needed. Args: mapname,k,v
        tc.set("map", getMapFunction); // get a map by name
        tc.set("listadd", addToListFunction); // append an item to a list, creating a new list if needed. Args: listname,v
        tc.set("list", getListFunction); // get a list by name
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
     * Choose a random string from a list of strings using the PRNG seeded by setPRNGSeed.
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

    public final IntIntToIntFunction randomFunction = (a, b) -> prng.nextInt(a, b);

    public final StringStringFunction dropFunction = (a) -> "";

    Map<String, Map<String,String>> maps = new HashMap<>();
    Map<String, List<String>> lists = new HashMap<>();

    public final StringStringStringToStringFunction addToMapFunction = (mapname, key, value) -> {
        Map<String, String> map = maps.computeIfAbsent(mapname,k -> new HashMap<>());
        map.put(key,value);
        return "";
    };
    public final StringObjectFunction getMapFunction = mapname -> maps.computeIfAbsent(mapname,k -> new HashMap<>());

    public final StringStringToStringFunction addToListFunction = (listname, value) -> {
        List<String> lst = lists.computeIfAbsent(listname,k -> new ArrayList<>());
        lst.add(value);
        return "";
    };

    public final StringObjectFunction getListFunction = listname -> lists.computeIfAbsent(listname, k-> new ArrayList<>());
}
