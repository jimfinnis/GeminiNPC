import io.marioslab.basis.template.Template;
import io.marioslab.basis.template.TemplateContext;
import io.marioslab.basis.template.TemplateLoader;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TemplateTest {
    @Test
    public void testTemplate() {
        // create a template loader that loads from a map, rather than from files.
        TemplateLoader.MapTemplateLoader loader = new TemplateLoader.MapTemplateLoader();
        // add a template to the loader
        loader.set("testTemplate", "greeting: {{greeting}}");
        // load the template
        Template t = loader.load("testTemplate");

        // now we can use the template by creating a TemplateContext
        TemplateContext tc = new TemplateContext();
        // setting a variable in the context
        tc.set("greeting", "Hello, world!");
        // render the template with the context
        String rendered = t.render(tc);
        // check that the rendered template is as expected
        assertEquals("greeting: Hello, world!", rendered);
    }

    /**
     * This test just demonstrates that if the template variable contains tags,
     * they won't get expanded.
     */
    @Test
    public void testTemplateVariableTagsNotExpanded(){
        TemplateLoader.MapTemplateLoader loader = new TemplateLoader.MapTemplateLoader();
        loader.set("testTemplate", "greeting: {{greeting}}");
        Template t = loader.load("testTemplate");

        TemplateContext tc = new TemplateContext();
        tc.set("greeting", "Hello, {{person}}!");
        tc.set("person", "Jim");

        String rendered = t.render(tc);
        assertEquals("greeting: Hello, {{person}}!", rendered);
    }

    /**
     * Check that if we use a template map loader, and reassign the template,
     * the new template is used.
     */
    @Test
    public void testTemplateReassigned() {
        // create a template loader that loads from a map, rather than from files.
        TemplateLoader.MapTemplateLoader loader = new TemplateLoader.MapTemplateLoader();

        // add a template to the loader
        loader.set("testTemplate", "greeting: {{greeting}}");
        Template t = loader.load("testTemplate");

        // now we can use the template by creating a TemplateContext, setting a variable in it, and rendering it
        TemplateContext tc = new TemplateContext();
        tc.set("greeting", "Hello, world!");
        String rendered = t.render(tc);
        assertEquals("greeting: Hello, world!", rendered);

        // replace the template in the loader and reload the template
        loader.set("testTemplate", "greeting 2: {{greeting}}");
        t = loader.load("testTemplate");
        // always create a new context - and then we can render
        tc = new TemplateContext();
        tc.set("greeting", "Hello, world!");
        rendered = t.render(tc);
        assertEquals("greeting 2: Hello, world!", rendered);
    }

    @Test
    public void testList(){
        TemplateLoader.MapTemplateLoader loader = new TemplateLoader.MapTemplateLoader();

        // add a template to the loader


        // it's really FUCKING ANNOYING that ArrayList.add returns a boolean; I have to drop it here
        // in an ugly way.
        loader.set("testTemplate", "{{list.add(\"wibble\")?\"\":\"\"}}greeting: {{greeting}}");
        Template t = loader.load("testTemplate");
        TemplateContext tc = new TemplateContext();
        tc.set("list", new ArrayList<String>());
        tc.set("greeting", "Hello, world!");
        String rendered = t.render(tc);
        assertEquals("greeting: Hello, world!", rendered);


    }
}
