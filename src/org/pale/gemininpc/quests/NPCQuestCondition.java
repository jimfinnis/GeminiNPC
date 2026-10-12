package org.pale.gemininpc.quests;

import org.bukkit.configuration.ConfigurationSection;
import org.pale.gemininpc.quests.expr.TreeNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

class NPCQuestCondition {
    TreeNode when;        // parsed condition as a root in a AST
    String prompt;      // string to add to the prompt for this NPC when condition holds

    /// Something the LLM can choose to do while the condition holds, e.g.
    ///
    ///     actions:
    ///         confess: { set: [helga_confessed, helga_exposed], desc: "You admit hanging the banner" }
    ///
    /// @param set the facts to set when the action is done
    /// @param desc the description given to the LLM, so that it knows when to do the action
    record Action(String name, List<String> set, String desc) {}

    /// actions by name, in the order they're in the file
    Map<String, Action> actions = new LinkedHashMap<>();

    /// @param where where this condition is in the quest file, for warnings
    public NPCQuestCondition(Quest q, String where, ConfigurationSection cs) {
        q.warnUnknownKeys(cs, where, Set.of("when", "prompt", "actions"));
        String whenstr = cs.getString("when");
        if(whenstr==null)
            throw new QuestException("no when:");
        when = q.parseExpression("when", whenstr);
        prompt = cs.getString("prompt");
        loadActions(q, where, cs);
        if(prompt==null && actions.isEmpty())
            q.warn(where, "no prompt: or actions:, so this does nothing");
    }

    private void loadActions(Quest q, String where, ConfigurationSection cs){
        if(!cs.contains("actions"))
            return;
        ConfigurationSection as = cs.getConfigurationSection("actions");
        if(as==null)
            throw new QuestException("actions must be a map of action names to actions");
        for(String name: as.getKeys(false)){
            String actionWhere = "actions." + name;
            QuestException.in(actionWhere, () -> {
                ConfigurationSection a = as.getConfigurationSection(name);
                if(a==null)
                    throw new QuestException("must contain set: and desc:");
                q.warnUnknownKeys(a, where + "." + actionWhere, Set.of("set", "desc"));
                // set: is the only thing an action can do at the moment, so it's required
                List<String> set = q.factNames(a, "set");
                if(set.isEmpty())
                    throw new QuestException("no set:, so this action would do nothing");
                // the LLM only has the description to decide when to do the action
                String desc = a.getString("desc");
                if(desc==null || desc.isBlank())
                    throw new QuestException("no desc:, which the LLM needs to know when to do this action");
                actions.put(name, new Action(name, set, desc));
            });
        }
    }
}
