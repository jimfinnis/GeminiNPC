package org.pale.gemininpc.quests.actions;

/// Something a quest does in response to an event, such as setting a fact. Actions come in lists (see
/// `ActionList`), which are used by triggers and, later, by `on-complete`.
public interface IQuestAction {
    void run(ActionContext ctx);
}
