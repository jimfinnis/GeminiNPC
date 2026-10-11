package org.pale.gemininpc.quests.expr;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

// These go through the parser as well, so they check the whole string-to-value path. The operators
// themselves are tested in detail in ValueTest; this is about the evaluator walking the tree.
public class EvaluatorTest {
    private static Value eval(String input) {
        return new Evaluator().evaluate(new Parser().parse(input));
    }

    private static void check(String input, Value expected) {
        Assertions.assertEquals(expected, eval(input), "Input: " + input);
    }

    private static void check(String input, int expected) {
        check(input, new Value.Int(expected));
    }

    @Test
    public void literals() {
        check("42", 42);
        check("'foo'", new Value.Str("foo"));
        check("\"foo\"", new Value.Str("foo"));
    }

    // An evaluator with some identifiers set, for the lookup tests
    private static Evaluator withValues() {
        Evaluator e = new Evaluator();
        e.set("gold", new Value.Int(50));
        e.set("level", new Value.Int(3));
        e.set("name", new Value.Str("Bob"));
        e.set("faction", new Value.Ident("rebels"));
        e.set("rebels", new Value.Ident("rebels"));
        e.set("empire", new Value.Ident("empire"));
        return e;
    }

    private static void checkWith(Evaluator e, String input, Value expected) {
        Assertions.assertEquals(expected, e.evaluate(new Parser().parse(input)), "Input: " + input);
    }

    @Test
    public void identifierLookup() {
        Evaluator e = withValues();
        checkWith(e, "gold", new Value.Int(50));
        checkWith(e, "name", new Value.Str("Bob"));
        checkWith(e, "faction", new Value.Ident("rebels"));
    }

    @Test
    public void identifiersInExpressions() {
        Evaluator e = withValues();
        checkWith(e, "gold + 10", new Value.Int(60));
        checkWith(e, "gold * level", new Value.Int(150));
        checkWith(e, "gold >= 15 * level and name == 'Bob'", Value.TRUE);
        checkWith(e, "level > 5 or gold < 10", Value.FALSE);
        checkWith(e, "'Hello ' + name", new Value.Str("Hello Bob"));
        // an Ident value is compared as-is, not looked up again
        checkWith(e, "faction == rebels", Value.TRUE);
        checkWith(e, "faction == empire", Value.FALSE);
    }

    @Test
    public void setReplacesValue() {
        Evaluator e = withValues();
        e.set("gold", new Value.Int(5));
        checkWith(e, "gold", new Value.Int(5));
        // and a value can change type
        e.set("gold", new Value.Str("lots"));
        checkWith(e, "gold", new Value.Str("lots"));
    }

    @Test
    public void unknownIdentifier() {
        Evaluator e = withValues();
        RuntimeException ex = Assertions.assertThrows(RuntimeException.class,
                () -> e.evaluate(new Parser().parse("gold + silver")));
        Assertions.assertEquals("unknown identifier silver", ex.getMessage());
        // an evaluator with nothing set knows no identifiers at all
        Assertions.assertThrows(RuntimeException.class, () -> eval("foo"));
    }

    @Test
    public void unaryMinus() {
        check("-5", -5);
        check("--5", 5);
        check("-2 * 3", -6);
        check("10 - -3", 13);
        check("-2 + 3", 1);
        check("-3 < -2", 1);
        checkWith(withValues(), "-gold + level", new Value.Int(-47));
    }

    @Test
    public void brackets() {
        check("(6 + 2) * 20", 160);
        check("6 + 2 * 20", 46);
        check("10 - (4 - 1)", 7);
        check("-(2 + 3)", -5);
        check("((1 + 2) * (3 + 4))", 21);
        check("not (1 and 0)", 1);
        check("(0 or 1) and 0", 0);
        checkWith(withValues(), "(gold + 10) / level", new Value.Int(20));
        // short-circuiting still works with a bracketed right side
        check("0 and (nosuchthing + 1)", 0);
    }

    @Test
    public void notOperator() {
        check("not 0", 1);
        check("not 1", 0);
        check("not 7", 0);
        check("not not 7", 1);
        check("not 1 == 2", 1);
        check("not 1 < 2 or 3 == 3", 1);
        check("not 1 < 2 and 3 == 3", 0);
        Evaluator e = withValues();
        checkWith(e, "not gold > 100", Value.TRUE);
        checkWith(e, "not name == 'Bob'", Value.FALSE);
        // not on the left of a short-circuiting operator: it decides, so the right isn't evaluated
        check("not 0 or nosuchthing", 1);
        check("not 1 and nosuchthing", 0);
    }

    @Test
    public void unaryErrors() {
        Assertions.assertThrows(RuntimeException.class, () -> eval("-'a'"));
        Assertions.assertThrows(RuntimeException.class, () -> eval("not 'a'"));
        Evaluator e = withValues();
        Assertions.assertThrows(RuntimeException.class, () -> e.evaluate(new Parser().parse("-name")));
        Assertions.assertThrows(RuntimeException.class, () -> e.evaluate(new Parser().parse("not faction")));
        Assertions.assertThrows(RuntimeException.class, () -> e.evaluate(new Parser().parse("-nosuchthing")));
    }

    @Test
    public void shortCircuit() {
        // the right side would throw if evaluated (unknown identifier, division by zero,
        // type error), so these only pass if it's skipped
        check("0 and nosuchthing", 0);
        check("1 or nosuchthing", 1);
        check("0 and 1 / 0", 0);
        check("1 or 1 / 0", 1);
        check("0 and 'a' * 2", 0);
        // the result is still 0 or 1 when the left side is any nonzero value
        check("7 or nosuchthing", 1);
        // short-circuiting inside a larger expression
        check("1 < 2 or nosuchthing > 3", 1);
        check("2 < 1 and nosuchthing > 3 or 5 == 5", 1);
        Evaluator e = withValues();
        checkWith(e, "level > 5 and gold / 0 > 1", Value.FALSE);
    }

    @Test
    public void noShortCircuitWhenLeftDoesntDecide() {
        // left side doesn't decide the result, so the right side is evaluated and its error happens
        Assertions.assertThrows(RuntimeException.class, () -> eval("1 and nosuchthing"));
        Assertions.assertThrows(RuntimeException.class, () -> eval("0 or 1 / 0"));
        check("1 and 0", 0);
        check("0 or 5", 1);
        // a non-Int left side never short-circuits: it's still a type error
        Assertions.assertThrows(RuntimeException.class, () -> eval("'a' or 1"));
        Assertions.assertThrows(RuntimeException.class, () -> eval("'a' or nosuchthing"));
    }

    @Test
    public void lookedUpValuesAreTypeChecked() {
        Evaluator e = withValues();
        Assertions.assertThrows(RuntimeException.class, () -> e.evaluate(new Parser().parse("gold + name")));
        Assertions.assertThrows(RuntimeException.class, () -> e.evaluate(new Parser().parse("faction == 'rebels'")));
    }

    @Test
    public void arithmetic() {
        check("2 + 3", 5);
        check("2 - 3", -1);
        check("4 * 3", 12);
        check("7 / 2", 3);
    }

    @Test
    public void precedence() {
        check("6 + 2 * 20", 46);
        check("6 * 2 + 20", 32);
        check("20 - 6 / 2", 17);
        check("1 + 2 * 3 - 4 / 2", 5);
    }

    @Test
    public void leftAssociativity() {
        // these would give different answers if evaluated right to left
        check("10 - 4 - 1", 5);
        check("100 / 10 / 5", 2);
        check("10 - 4 + 1", 7);
    }

    @Test
    public void comparisons() {
        check("1 < 2", 1);
        check("2 < 1", 0);
        check("2 + 2 == 4", 1);
        check("2 * 3 != 6", 0);
        check("10 >= 5 * 2", 1);
        check("'apple' < 'banana'", 1);
    }

    @Test
    public void booleans() {
        check("1 < 2 and 3 == 3", 1);
        check("1 < 2 and 3 == 4", 0);
        check("1 > 2 or 3 == 3", 1);
        // and binds tighter than or
        check("1 or 0 and 0", 1);
        check("0 and 1 or 1", 1);
        check("0 and 1 or 0", 0);
    }

    @Test
    public void strings() {
        check("'foo' + 'bar'", new Value.Str("foobar"));
        check("'a' + 'b' + 'c'", new Value.Str("abc"));
        check("'foo' + 'bar' == 'foobar'", 1);
    }

    @Test
    public void errors() {
        Assertions.assertThrows(RuntimeException.class, () -> eval("1 + 'x'"));
        Assertions.assertThrows(RuntimeException.class, () -> eval("'a' * 2"));
        Assertions.assertThrows(RuntimeException.class, () -> eval("1 / 0"));
        // error in the right subtree, after the left operand's value has been pushed
        Assertions.assertThrows(RuntimeException.class, () -> eval("2 + 1 * 'x'"));
        Assertions.assertThrows(RuntimeException.class, () -> eval("1 + 2 * 3 - 4 / 0"));
    }

    @Test
    public void reusableAfterError() {
        // one Evaluator, with an error partway through a tree in between good evaluations
        Evaluator e = new Evaluator();
        Parser p = new Parser();
        Assertions.assertEquals(new Value.Int(3), e.evaluate(p.parse("1 + 2")));
        Assertions.assertThrows(RuntimeException.class, () -> e.evaluate(p.parse("5 + 2 * 'x'")));
        Assertions.assertEquals(new Value.Int(7), e.evaluate(p.parse("3 + 4")));
        Assertions.assertEquals(new Value.Str("ok"), e.evaluate(p.parse("'ok'")));
    }
}
