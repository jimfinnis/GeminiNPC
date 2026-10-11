package org.pale.gemininpc.quests.triggers;

import org.pale.gemininpc.quests.Instance;
import org.pale.gemininpc.quests.expr.Value;

public class SetAction implements ITriggerAction {
    String factName;
    public SetAction(String astr) {
        factName = astr;
    }

    @Override
    public void run(Instance inst) {
        inst.set(factName, Value.TRUE);
    }
}
