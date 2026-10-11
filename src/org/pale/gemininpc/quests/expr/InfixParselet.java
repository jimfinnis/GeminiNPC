package org.pale.gemininpc.quests.expr;

abstract class InfixParselet {
    protected int precedence;

    int getPrecedence() {
        return precedence;
    }
    abstract TreeNode parse(Parser p, TreeNode left, Token t);

    static class BinaryOperatorParselet extends InfixParselet {
        String subtype;
        boolean is_right_assoc;

        BinaryOperatorParselet(String subtype, int precedence, boolean is_right_assoc){
            this.subtype = subtype;
            this.precedence = precedence;
            this.is_right_assoc = is_right_assoc;
        }

        public TreeNode parse(Parser p, TreeNode left, Token t){
            int prec = is_right_assoc ? precedence -1 : precedence;
            TreeNode right = p.parseExpression(prec);
            return new TreeNode.BinopNode(((Token.OperatorToken)t).s, left, right);
        }
    }
}

