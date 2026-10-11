package org.pale.gemininpc.quests;

import org.bukkit.configuration.ConfigurationSection;
import org.pale.gemininpc.Plugin;
import org.pale.gemininpc.quests.expr.Parser;
import org.pale.gemininpc.quests.expr.TreeNode;

class NPCQuestCondition {
    TreeNode when;        // parsed condition as a root in a AST
    String prompt;      // string to add to the prompt for this NPC when condition holds

    // add actions here - things the NPC can do. These should get added to the prompt.
    // crude example:
    //  actions:
    //      confess: { set: [helga_confessed, helga_exposed], desc: "You admit hanging the banner" }

    public NPCQuestCondition(Quest q, ConfigurationSection cs) {
        String whenstr = cs.getString("when");
        if(whenstr==null)
            throw new RuntimeException("No condition provided for NPC");
        // parse that expression
        Parser parser = new Parser();
        // we need to tell the parser about the variables in the quest
        parser.registerVariables(q.facts.keySet());
        when = parser.parse(whenstr);
        prompt = cs.getString("prompt");
    }
}
