package org.pale.gemininpc.quests.expr;

import java.util.*;

public class Parser {
    class Exception extends RuntimeException {
        public Exception(String message) {
            super(message);
        }
    }

    Lexer lexer;

    // every token that isn't an operator (integer, string or identifier) is a literal, and they
    // all share this parselet, which makes a ValueNode
    PrefixParselet valueParselet = new PrefixParselet.ValueParselet();

    // operator parselets, keyed by the operator string. Anything triggered by an operator token
    // goes in one of these, not just unary and binary operators: "(" is in the prefix map for
    // grouping, and could also go in the infix map one day for function calls like "f(x)".
    Map<String, InfixParselet> infixOperatorParselets = new HashMap<>();
    Map<String, PrefixParselet> prefixOperatorParselets = new HashMap<>();

    // variables this parser knows about
    Set<String> variables = new HashSet<>();

    public Parser() {
        // prefix operator parselets
        prefixOperatorParselets.put("-", new PrefixParselet.UnaryOperatorParselet("-", 175));
        // "not" binds looser than comparisons but tighter than and/or, so "not a == b and c" is
        // "(not (a == b)) and c"
        prefixOperatorParselets.put("not", new PrefixParselet.UnaryOperatorParselet("not", 55));
        prefixOperatorParselets.put("(", new PrefixParselet.GroupParselet());

        // register operator infix parselets
        infixOperatorParselets.put("*", new InfixParselet.BinaryOperatorParselet("*", 150, false));
        infixOperatorParselets.put("/", new InfixParselet.BinaryOperatorParselet("/", 150, false));
        infixOperatorParselets.put("+", new InfixParselet.BinaryOperatorParselet("+", 100, false));
        infixOperatorParselets.put("-", new InfixParselet.BinaryOperatorParselet("-", 100, false));
        // comparisons bind tighter than equality, as in C/Java, so "a < b == c < d" is "(a < b) == (c < d)"
        infixOperatorParselets.put("<", new InfixParselet.BinaryOperatorParselet("<", 70, false));
        infixOperatorParselets.put(">", new InfixParselet.BinaryOperatorParselet(">", 70, false));
        infixOperatorParselets.put("<=", new InfixParselet.BinaryOperatorParselet("<=", 70, false));
        infixOperatorParselets.put(">=", new InfixParselet.BinaryOperatorParselet(">=", 70, false));
        infixOperatorParselets.put("==", new InfixParselet.BinaryOperatorParselet("==", 60, false));
        infixOperatorParselets.put("!=", new InfixParselet.BinaryOperatorParselet("!=", 60, false));
        infixOperatorParselets.put("and", new InfixParselet.BinaryOperatorParselet("and", 50, false));
        infixOperatorParselets.put("or", new InfixParselet.BinaryOperatorParselet("or", 40, false));
    }

    public void registerVariables(Collection<String> s){
        variables.addAll(s);
    }

    /// Consume the next token, which must be the operator `expected` (e.g. a closing bracket).
    public void expectOperator(String expected) {
        if(!lexer.hasNext())
            throw new Exception("expected " + expected + " but the expression ended");
        Token t = lexer.next();
        if(t instanceof Token.OperatorToken op) {
            if(op.s.equals(expected))
                return;
        }
        throw new Exception("expected " + expected + " but found " + show(t));
    }

    /// A token as it appeared in the source, for error messages: `)` rather than `OP:)`.
    private static String show(Token t) {
        return switch (t) {
            case Token.IntToken i -> Integer.toString(i.n);
            case Token.StringToken s -> "'" + s.s + "'";
            case Token.IdentifierToken id -> id.s;
            case Token.OperatorToken o -> o.s;
        };
    }


    public TreeNode parse(String s) {
        lexer = new Lexer(s);

        TreeNode r = parseExpression(0);
        if(lexer.hasNext())
            throw new Exception("unexpected " + show(lexer.peek()) + " after end of expression");
        return r;
    }

    /// The infix parselet for a token, or null if it can't continue an expression. Only operators
    /// can be infix.
    private InfixParselet getInfixParselet(Token t){
        if(t instanceof Token.OperatorToken o)
            return infixOperatorParselets.get(o.s);
        return null;
    }

    /// The prefix parselet for a token, or null if it can't start an expression (an operator
    /// with no prefix parselet). Anything that isn't an operator is a literal.
    private PrefixParselet getPrefixParselet(Token t){
        if(t instanceof Token.OperatorToken o)
            return prefixOperatorParselets.get(o.s);
        return valueParselet;
    }

    private int getNextTokenPrecedence(){
        // get the precedence of the next token, or zero if it doesn't
        // have an infix parser or there isn't a next token.
        if(!lexer.hasNext())
            return 0;
        InfixParselet ip = getInfixParselet(lexer.peek());
        return (ip==null) ? 0 : ip.getPrecedence();
    }

    TreeNode parseExpression(int precedence) {
        if(!lexer.hasNext())
            throw new Exception("expected a value but the expression ended");
        Token t = lexer.next();
        // try to get a prefix parser - first token in expr must be one of these
        PrefixParselet pp = getPrefixParselet(t);
        if(pp==null){
            throw new Exception("expected a value but found " + show(t));
        }
        // parse it
        TreeNode left = pp.parse(this, t);
        // and now the Pratt parser mechanism.
        while(precedence < getNextTokenPrecedence()){
            // get that next token
            if(!lexer.hasNext())
                throw new Exception("expected an operator but the expression ended");
            t = lexer.next();
            // and its parser
            InfixParselet ip = getInfixParselet(t);
            if(ip==null)
                throw new Exception("Cannot find infix parselet for "+t);
            // and do the infix parse; we pass the token so we can find the actual operation for binary ops.
            left = ip.parse(this, left, t);
        }
        return left;
    }
}