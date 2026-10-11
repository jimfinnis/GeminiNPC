package org.pale.gemininpc.quests.expr;

import java.util.HashMap;
import java.util.Map;
import java.util.Stack;

public class Evaluator {
    // values - these appear as identifiers in the tree
    private Map<String, Value> values = new HashMap<>();

    private Stack<Value> stack = new Stack<>();

    /// Set the value an identifier evaluates to. The value can itself be an `Ident`, which is
    /// used as-is and not looked up again.
    public void set(String name, Value v) {
        values.put(name, v);
    }

    /// Evaluate a whole expression tree and return its value.
    public Value evaluate(TreeNode root) {
        // clear out anything left behind by an earlier evaluation that threw partway through
        stack.clear();
        push(root);
        return stack.pop();
    }

    /// Evaluate a node, leaving its value on top of the stack. An identifier is replaced by its
    /// value from `values`, and it's an error if it has none. A binop evaluates its left then
    /// right children first, so their values are the top two items when it applies the operator.
    /// A unop likewise evaluates its child, then replaces it with the result of the operator.
    ///
    /// `and` and `or` short-circuit: if the left side is an `Int` that already decides the result
    /// (0 for `and`, nonzero for `or`), the right side isn't evaluated at all, so errors in it
    /// (unknown identifiers, type errors, division by zero) don't happen. The left side must be an
    /// `Int` to short-circuit; anything else falls through to `binop`, which gives the type error.
    private void push(TreeNode node) {
        if (node instanceof TreeNode.ValueNode n) {
            if (n.value instanceof Value.Ident(String name)) {
                Value v = values.get(name);
                if (v == null)
                    throw new RuntimeException("unknown identifier " + name);
                stack.push(v);
            } else
                stack.push(n.value);
        } else if(node instanceof TreeNode.BinopNode n) {
            push(n.left);
            // this handles short-circuiting for boolean binops
            if (stack.peek() instanceof Value.Int(int left)) {
                if (n.op.equals("and") && left == 0) {
                    stack.pop();
                    stack.push(Value.FALSE);
                    return;
                }
                if (n.op.equals("or") && left != 0) {
                    stack.pop();
                    stack.push(Value.TRUE);
                    return;
                }
            }
            push(n.right);
            Value a,b;
            b = stack.pop();
            a = stack.pop();
            stack.push(a.binop(n.op, b));
        } else if (node instanceof TreeNode.UnopNode n) {
            push(n.child);
            stack.push(stack.pop().unop(n.op));
        } else {
            // without this, a new node type would silently push nothing and unbalance the stack
            throw new IllegalStateException("unknown node class " + node.getClass().getSimpleName());
        }
    }
}
