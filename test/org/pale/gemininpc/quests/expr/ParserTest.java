package org.pale.gemininpc.quests.expr;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class ParserTest {
    // Flatten a tree into a Lisp-style string so a whole tree can be checked in one assertion:
    // numbers and identifiers are written as-is, strings are in double quotes, and a binary
    // operator node is "(op left right)".
    // So "6 + 2 * 20" should give "(+ 6 (* 2 20))".
    private static String sexpr(TreeNode n) {
        return switch (n) {
            case TreeNode.ValueNode v -> switch (v.value) {
                case Value.Int(int i) -> Integer.toString(i);
                case Value.Ident(String name) -> name;
                case Value.Str(String s) -> "\"" + s + "\"";            };
            case TreeNode.BinopNode b -> "(" + b.op + " " + sexpr(b.left) + " " + sexpr(b.right) + ")";
            // unary ops are told apart from binary ones by having one operand: "(- a)" vs "(- a b)"
            case TreeNode.UnopNode u -> "(" + u.op + " " + sexpr(u.child) + ")";
            default -> throw new IllegalStateException("unknown node class " + n.getClass());
        };
    }

    // Assert a node is a ValueNode and return the Value it holds.
    private static Value valueOf(TreeNode n) {
        return Assertions.assertInstanceOf(TreeNode.ValueNode.class, n).value;
    }

    private static void check(String input, String expected) {
        Assertions.assertEquals(expected, sexpr(new Parser().parse(input)), "Input: " + input);
    }

    @Test
    public void emptyString() {
        Parser p = new Parser();
        Assertions.assertThrows(Parser.Exception.class, () -> p.parse(""));
        Assertions.assertThrows(Parser.Exception.class, () -> p.parse("   "));
    }

    @Test
    public void justNumber() {
        Assertions.assertEquals(new Value.Int(6), valueOf(new Parser().parse("6")));
    }

    @Test
    public void justIdentifier() {
        Assertions.assertEquals(new Value.Ident("foo"), valueOf(new Parser().parse("foo")));
    }

    @Test
    public void justString() {
        // strings get their own kind of value, distinct from identifiers
        Value v = valueOf(new Parser().parse("\"foo\""));
        Assertions.assertEquals(new Value.Str("foo"), v);
        Assertions.assertNotEquals(new Value.Ident("foo"), v);
        check("name + 'bar'", "(+ name \"bar\")");
    }

    @Test
    public void sum() {
        // check the node structure directly once, the rest of the tests use sexpr()
        TreeNode tn = new Parser().parse("6+foo");
        TreeNode.BinopNode b = Assertions.assertInstanceOf(TreeNode.BinopNode.class, tn);
        Assertions.assertEquals("+", b.op);
        Assertions.assertEquals(new Value.Int(6), valueOf(b.left));
        Assertions.assertEquals(new Value.Ident("foo"), valueOf(b.right));

        check("6 - foo", "(- 6 foo)");
        check("a * b", "(* a b)");
        check("a / b", "(/ a b)");
    }

    @Test
    public void booleanOps() {
        check("6 and foo", "(and 6 foo)");
        check("6 or foo", "(or 6 foo)");
        // and binds tighter than or, whichever comes first
        check("a or b and c", "(or a (and b c))");
        check("a and b or c", "(or (and a b) c)");
        check("a and b or c and d", "(or (and a b) (and c d))");
    }

    @Test
    public void comparisonOps() {
        check("a == b", "(== a b)");
        check("a != b", "(!= a b)");
        check("a < b", "(< a b)");
        check("a > b", "(> a b)");
        check("a <= b", "(<= a b)");
        check("a >= b", "(>= a b)");
        check("name == \"Bob\"", "(== name \"Bob\")");
        // no spaces needed, and the lexer must not split the two-char operators
        check("a<=b", "(<= a b)");
        check("a!=b", "(!= a b)");
    }

    @Test
    public void comparisonPrecedence() {
        // arithmetic binds tighter than comparison
        check("a + 1 < b * 2", "(< (+ a 1) (* b 2))");
        check("a - b == c", "(== (- a b) c)");
        // comparison binds tighter than equality
        check("a < b == c > d", "(== (< a b) (> c d))");
        check("a != b <= c", "(!= a (<= b c))");
        // equality binds tighter than and/or
        check("a == 1 and b != 2", "(and (== a 1) (!= b 2))");
        check("a < 1 or b >= 2 and c == d",
                "(or (< a 1) (and (>= b 2) (== c d)))");
    }

    @Test
    public void precedence() {
        check("6 + 2 * 20", "(+ 6 (* 2 20))");
        check("6 * 2+ 20", "(+ (* 6 2) 20)");
        check("a + b * c - d", "(- (+ a (* b c)) d)");
        check("a / b + c * d", "(+ (/ a b) (* c d))");
        // arithmetic binds tighter than and/or
        check("a + 1 and b * 2", "(and (+ a 1) (* b 2))");
        check("a or b + c", "(or a (+ b c))");
    }

    @Test
    public void unaryMinus() {
        check("-1", "(- 1)");
        check("--1", "(- (- 1))");
        check("- a", "(- a)");
        // binds tighter than everything else
        check("-a * b", "(* (- a) b)");
        check("a * -b", "(* a (- b))");
        check("-a + b", "(+ (- a) b)");
        check("-a < b", "(< (- a) b)");
        // the same token is binary after an operand and unary where an operand is expected
        check("a - -b", "(- a (- b))");
        check("a--b", "(- a (- b))");
        check("a - b", "(- a b)");
    }

    @Test
    public void brackets() {
        // brackets make no node of their own, they just change the shape of the tree
        check("(a)", "a");
        check("((a))", "a");
        check("(6 + 2) * 20", "(* (+ 6 2) 20)");
        check("6 * (2 + 20)", "(* 6 (+ 2 20))");
        check("a - (b - c)", "(- a (- b c))");
        check("((a + b) * (c - d))", "(* (+ a b) (- c d))");
        check("-(a + b)", "(- (+ a b))");
        check("not (a and b)", "(not (and a b))");
        check("(not a) == b", "(== (not a) b)");
        check("(a or b) and c", "(and (or a b) c)");
        // no spaces needed
        check("(a+b)*c", "(* (+ a b) c)");
    }

    @Test
    public void bracketErrors() {
        Parser p = new Parser();
        Assertions.assertThrows(Parser.Exception.class, () -> p.parse("("));
        Assertions.assertThrows(Parser.Exception.class, () -> p.parse("(a"));
        Assertions.assertThrows(Parser.Exception.class, () -> p.parse("(a + b"));
        Assertions.assertThrows(Parser.Exception.class, () -> p.parse("((a)"));
        Assertions.assertThrows(Parser.Exception.class, () -> p.parse("a)"));
        Assertions.assertThrows(Parser.Exception.class, () -> p.parse("(a))"));
        Assertions.assertThrows(Parser.Exception.class, () -> p.parse("()"));
        Assertions.assertThrows(Parser.Exception.class, () -> p.parse(")"));
        Assertions.assertThrows(Parser.Exception.class, () -> p.parse("(a b)"));
        Assertions.assertThrows(Parser.Exception.class, () -> p.parse("(a + )"));
    }

    // Assert parsing fails with exactly this message
    private static void errorMessage(String input, String expected) {
        Parser.Exception e = Assertions.assertThrows(Parser.Exception.class, () -> new Parser().parse(input),
                "Input: " + input);
        Assertions.assertEquals(expected, e.getMessage(), "Input: " + input);
    }

    @Test
    public void errorMessages() {
        // tokens are shown as they appeared in the source, not as e.g. "OP:)"
        errorMessage("", "expected a value but the expression ended");
        errorMessage("6 +", "expected a value but the expression ended");
        errorMessage("(", "expected a value but the expression ended");
        errorMessage("not", "expected a value but the expression ended");
        errorMessage("(a", "expected ) but the expression ended");
        errorMessage("(a b)", "expected ) but found b");
        errorMessage("(a 'b')", "expected ) but found 'b'");
        errorMessage("(a 6)", "expected ) but found 6");
        errorMessage("()", "expected a value but found )");
        errorMessage("(a + )", "expected a value but found )");
        errorMessage("* 2", "expected a value but found *");
        errorMessage("a)", "unexpected ) after end of expression");
        errorMessage("6 6", "unexpected 6 after end of expression");
    }

    @Test
    public void notOperator() {
        check("not a", "(not a)");
        check("not not a", "(not (not a))");
        // binds looser than comparisons and arithmetic...
        check("not a == b", "(not (== a b))");
        check("not a + 1 < b", "(not (< (+ a 1) b))");
        // ...but tighter than and/or
        check("not a and b", "(and (not a) b)");
        check("not a or not b", "(or (not a) (not b))");
        check("a and not b == c", "(and a (not (== b c)))");
        // works with unary minus
        check("not -a", "(not (- a))");
        check("-a == not b", "(== (- a) (not b))");
    }

    @Test
    public void leftAssociative() {
        check("a - b - c", "(- (- a b) c)");
        check("a / b / c", "(/ (/ a b) c)");
        check("a - b + c", "(+ (- a b) c)");
        check("a and b and c", "(and (and a b) c)");
        check("a or b or c", "(or (or a b) c)");
        check("a == b != c", "(!= (== a b) c)");
        check("a < b < c", "(< (< a b) c)");
    }

    @Test
    public void parserIsReusable() {
        // the same Parser should give independent results for successive parses
        Parser p = new Parser();
        Assertions.assertEquals("(+ 1 2)", sexpr(p.parse("1 + 2")));
        Assertions.assertEquals("(* a b)", sexpr(p.parse("a * b")));
    }

    @Test
    public void errors() {
        Parser p = new Parser();
        // operator with nothing after it
        Assertions.assertThrows(Parser.Exception.class, () -> p.parse("6 +"));
        // expression can't start with an operator, other than the unary ones (- and not)
        Assertions.assertThrows(Parser.Exception.class, () -> p.parse("+ 6"));
        Assertions.assertThrows(Parser.Exception.class, () -> p.parse("6 + * 2"));
        Assertions.assertThrows(Parser.Exception.class, () -> p.parse("! a"));
        // unary operator with nothing after it
        Assertions.assertThrows(Parser.Exception.class, () -> p.parse("-"));
        Assertions.assertThrows(Parser.Exception.class, () -> p.parse("a and not"));
        // "not" is only a prefix operator
        Assertions.assertThrows(Parser.Exception.class, () -> p.parse("a not b"));
        // leftover tokens after a complete expression
        Assertions.assertThrows(Parser.Exception.class, () -> p.parse("6 6"));
        // operators the lexer knows but the parser doesn't yet
        Assertions.assertThrows(Parser.Exception.class, () -> p.parse("6 % 6"));
        Assertions.assertThrows(Parser.Exception.class, () -> p.parse("a && b"));
        // a lone "=" isn't an operator - only "==" is
        Assertions.assertThrows(Parser.Exception.class, () -> p.parse("a = b"));
    }
}
