package org.pale.gemininpc.quests.expr;

abstract class PrefixParselet {
    abstract TreeNode parse(Parser p, Token t);

    /// Turns an integer, string or identifier token into a `ValueNode` holding the matching `Value`.
    static class ValueParselet extends PrefixParselet {
        public TreeNode parse(Parser p, Token t) {
            Value v = switch (t) {
                case Token.IntToken i -> new Value.Int(i.n);
                case Token.StringToken s -> new Value.Str(s.s);
                case Token.IdentifierToken id -> {
                    if(p.variables.contains(id.s))
                        yield new Value.Ident(id.s);
                    else
                        throw new Parser.Exception("unknown identifier " + id.s);
                }
                case Token.OperatorToken o -> throw new IllegalArgumentException("not a value token: " + o);
            };
            return new TreeNode.ValueNode(v);
        }
    }

    static class UnaryOperatorParselet extends PrefixParselet {
        String op;
        int precedence;
        public UnaryOperatorParselet(String op, int precedence){
            this.op = op;
            this.precedence = precedence;
        }

        public TreeNode parse(Parser p, Token t){
            // the operand is everything binding tighter than this operator
            TreeNode child = p.parseExpression(precedence);
            return new TreeNode.UnopNode(op, child);
        }
    }

    static class GroupParselet extends PrefixParselet {
        public TreeNode parse(Parser p, Token t){
            TreeNode e = p.parseExpression(0);
            p.expectOperator(")");
            return e;
        }
    }
}
