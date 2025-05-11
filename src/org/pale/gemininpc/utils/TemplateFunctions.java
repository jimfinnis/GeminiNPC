package org.pale.gemininpc.utils;

import io.marioslab.basis.template.TemplateContext;
import org.mcmonkey.sentinel.SentinelTrait;
import org.pale.gemininpc.GeminiNPCTrait;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Useful functions for templates wrapped in a class.
 */
public class TemplateFunctions {
    GeminiNPCTrait trait;
    Random prng;
    
    public TemplateFunctions(GeminiNPCTrait trait) {
        this.trait = trait;
        prng = new Random(trait.getNPC().getName().hashCode());
    }

    // function that takes a List<String> and returns a string
    @FunctionalInterface
    public interface ListStringFunction {
        @SuppressWarnings("unused")
        String apply(List<String> arg);
    }
    // takes a list,int,string and returns a string
    @FunctionalInterface
    public interface ListStringIntStringFunction {
        @SuppressWarnings("unused")
        String apply(List<String> arg1, int arg2, String s);
    }

    // annoyingly it seems it really needs the method to be called "apply", so we can't use
    // the standard java.util.function.Function interface. So we have to define our own.
    @FunctionalInterface
    public interface IntIntToIntFunction {
        @SuppressWarnings("unused")
        int apply(int arg1, int arg2);
    }

    @FunctionalInterface
    public interface BoolNoArgsFunction {
        @SuppressWarnings("unused")
        boolean apply();
    }


    /**
     * Add functions to the template context. This is run every time the template is used!
     * @param tc the template context to add to
     */
    public void addFunctions(TemplateContext tc){

        tc.set("choose", stringChooseFunction);
        tc.set("pick", pickFunction);
        tc.set("random", randomFunction);
        tc.set("isSentinel", isSentinel);
    }

    /**
     * Choose a random string from a list of strings using the PRNG seeded by setPRNGSeed.
     */
    private final ListStringFunction stringChooseFunction = (args) -> {
        if(args.isEmpty()) return "";
        if(args.size() == 1) return args.get(0);
        int i = prng.nextInt(args.size());
        System.out.println("Choosing " + i + " from " + args);
        return args.get(i);
    };

    /**
     * Given a list of strings, a count and a delimiter string, pick that many random strings from the list.
     * They must all be different. Separate with newlines.
     */
    private final ListStringIntStringFunction pickFunction = (args, count, s) -> {
        if (args.isEmpty()) return "";
        if (args.size() == 1) return args.get(0);
        if (count > args.size()) count = args.size();
        StringBuilder sb = new StringBuilder();
        args = new ArrayList<>(args); // make a copy of the list
        for (int i = 0; i < count; i++) {
            int j = prng.nextInt(args.size());
            sb.append(args.get(j));
            if (i < count - 1) sb.append(s);
            args.remove(j);
        }
        return sb.toString();
    };

    /**
     * Given two ints return random.nextint(a,b) - a is inclusive, b is exclusive.
     */

    public final IntIntToIntFunction randomFunction = (a, b) -> prng.nextInt(a, b);

    public final BoolNoArgsFunction isSentinel = () -> {
        if (trait == null) return false;
        return trait.getNPC().hasTrait(SentinelTrait.class);
    };
}
