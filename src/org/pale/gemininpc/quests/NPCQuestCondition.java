package org.pale.gemininpc.quests;

import org.bukkit.configuration.ConfigurationSection;
import org.pale.gemininpc.quests.actions.ActionList;
import org.pale.gemininpc.quests.expr.TreeNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

class NPCQuestCondition {
    TreeNode when;        // parsed condition as a root in a AST
    String prompt;      // string to add to the prompt for this NPC when condition holds

    /// Something the LLM can choose to do while the condition holds, e.g.
    ///
    ///     actions:
    ///       confess:
    ///         desc: You admit to the player that you hung the banner
    ///         do:
    ///           - set: [helga_confessed, helga_exposed]
    ///
    /// @param desc the description given to the LLM: the event which the action records
    /// @param actions what happens when the LLM does the action. This is the same kind of list as a
    ///                trigger's, so one LLM action can do several things (e.g. set facts and give an item),
    ///                which matters because a reply can only name one action.
    record Action(String name, String desc, ActionList actions) {}

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
                    throw new QuestException("must contain desc: and do:");
                q.warnUnknownKeys(a, where + "." + actionWhere, Set.of("desc", "do"));
                // the LLM needs the description to know what event the action records
                String desc = a.getString("desc");
                if(desc==null || desc.isBlank())
                    throw new QuestException("no desc:, which the LLM needs to know when to do this action");
                ActionList list = new ActionList(q, a, "do");
                if(list.isEmpty())
                    throw new QuestException("no do: actions, so this action would do nothing");
                actions.put(name, new Action(name, desc, list));
            });
        }
    }
}
