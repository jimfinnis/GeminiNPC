package org.pale.gemininpc.quests.expr;

import java.util.ArrayList;
import java.util.List;

public abstract class TreeNode {

    void prettyPrint(){
        prettyPrint(0);
    }


    protected void printPadLeft(int pad, String s){
        System.out.println(" ".repeat(pad)+s);
    }

    abstract void prettyPrint(int depth);

    /// A literal: an integer, string or identifier, held as the `Value` it represents.
    static class ValueNode extends TreeNode {
        Value value;
        ValueNode(Value v) {
            value = v;
        }
        void prettyPrint(int depth){
            printPadLeft(depth, "Value: "+value);
        }
    }

    static class BinopNode extends TreeNode {
        String op;
        TreeNode left;
        TreeNode right;
        BinopNode(String op, TreeNode l, TreeNode r){
            this.op = op;
            left = l;
            right = r;
        }
        void prettyPrint(int depth){
            printPadLeft(depth, "Binop: "+op);
            printPadLeft(depth+1,"Left:");
            left.prettyPrint(depth+2);
            printPadLeft(depth+1,"Right:");
            right.prettyPrint(depth+2);
        }
    }

    static class UnopNode extends TreeNode {
        String op;
        TreeNode child;
        UnopNode(String op, TreeNode child){
            this.op = op;
            this.child = child;
        }
        void prettyPrint(int depth){
            printPadLeft(depth, "Unop: "+op);
            printPadLeft(depth+1, "Child:");
            child.prettyPrint(depth+2);
        }
    }
}