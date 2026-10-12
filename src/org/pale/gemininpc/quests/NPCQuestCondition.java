package org.pale.gemininpc.quests;

import org.bukkit.configuration.ConfigurationSection;
import org.pale.gemininpc.quests.expr.Parser;
import org.pale.gemininpc.quests.expr.TreeNode;

import java.util.Set;

class NPCQuestCondition {
    TreeNode when;        // parsed condition as a root in a AST
    String prompt;      // string to add to the prompt for this NPC when condition holds

    // add actions here - things the NPC can do. These should get added to the prompt.
    // crude example:
    //  actions:
    //      confess: { set: [helga_confessed, helga_exposed], desc: "You admit hanging the banner" }

    /// @param where where this condition is in the quest file, for warnings
    public NPCQuestCondition(Quest q, String where, ConfigurationSection cs) {
        // "actions" isn't loaded yet, but it's allowed so that it doesn't produce a warning
        q.warnUnknownKeys(cs, where, Set.of("when", "prompt", "actions"));
        String whenstr = cs.getString("when");
        if(whenstr==null)
            throw new QuestException("no when:");
        // parse that expression
        Parser parser = new Parser();
        // we need to tell the parser about the variables in the quest
        parser.registerVariables(q.facts.keySet());
        try {
            when = parser.parse(whenstr);
        } catch (RuntimeException e) {
            throw new QuestException("when: " + e.getMessage() + ", in \"" + whenstr + "\"");
        }
        prompt = cs.getString("prompt");
        if(prompt==null && !cs.contains("actions"))
            q.warn(where, "no prompt: or actions:, so this does nothing");
    }
}
