package org.pale.gemininpc.quests.actions;

import org.pale.gemininpc.quests.expr.Value;

import java.util.List;

/// `set: FACT` or `set: [FACT, FACT...]`: set boolean facts to true. Later we should be able to set
/// non-boolean values. We could make use of the expression system to do this, with assignment operators
/// (and with access to assignment operators gated on the type of expression we are parsing).
public class SetAction implements IQuestAction {
    final List<String> factNames;

    /// @param factNames facts which have already been checked to exist in the quest
    public SetAction(List<String> factNames) {
        this.factNames = factNames;
    }

    @Override
    public void run(ActionContext ctx) {
        for(String f: factNames)
            ctx.instance().set(f, Value.TRUE);
    }
}
