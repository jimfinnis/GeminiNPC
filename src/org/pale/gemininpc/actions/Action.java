package org.pale.gemininpc.actions;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * This class is used to annotate actions which can be performed by the LLM
 */

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)

public @interface Action {
    String name() default "";       // the name of the action, by default the name of the method.
    String usage();                 // usage info e.g. "give ITEM"
    String desc();                  // description
    String group() default "default";   // what action group?
}

