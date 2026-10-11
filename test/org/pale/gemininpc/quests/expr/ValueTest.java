package org.pale.gemininpc.quests.expr;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class ValueTest {
    private static Value i(int n) { return new Value.Int(n); }
    private static Value s(String str) { return new Value.Str(str); }
    private static Value id(String name) { return new Value.Ident(name); }

    private static void check(Value l, String op, Value r, Value expected) {
        Assertions.assertEquals(expected, l.binop(op, r), l + " " + op + " " + r);
    }

    // Assert that an operation fails with a type error mentioning both kinds of value
    private static void typeError(Value l, String op, Value r) {
        RuntimeException e = Assertions.assertThrows(RuntimeException.class, () -> l.binop(op, r),
                l + " " + op + " " + r);
        Assertions.assertTrue(e.getMessage().startsWith("cannot "), e.getMessage());
    }

    @Test
    public void intArithmetic() {
        check(i(2), "+", i(3), i(5));
        check(i(2), "-", i(3), i(-1));
        check(i(4), "*", i(3), i(12));
        check(i(7), "/", i(2), i(3));
        check(i(-7), "/", i(2), i(-3));
    }

    @Test
    public void divisionByZero() {
        RuntimeException e = Assertions.assertThrows(RuntimeException.class, () -> i(1).binop("/", i(0)));
        Assertions.assertEquals("division by zero", e.getMessage());
    }

    @Test
    public void intComparison() {
        check(i(1), "<", i(2), Value.TRUE);
        check(i(2), "<", i(2), Value.FALSE);
        check(i(3), ">", i(2), Value.TRUE);
        check(i(2), ">", i(2), Value.FALSE);
        check(i(2), "<=", i(2), Value.TRUE);
        check(i(3), "<=", i(2), Value.FALSE);
        check(i(2), ">=", i(2), Value.TRUE);
        check(i(1), ">=", i(2), Value.FALSE);
        check(i(2), "==", i(2), Value.TRUE);
        check(i(2), "==", i(3), Value.FALSE);
        check(i(2), "!=", i(3), Value.TRUE);
        check(i(2), "!=", i(2), Value.FALSE);
    }

    @Test
    public void intLogic() {
        check(i(1), "and", i(1), Value.TRUE);
        check(i(1), "and", i(0), Value.FALSE);
        check(i(0), "and", i(1), Value.FALSE);
        check(i(1), "or", i(0), Value.TRUE);
        check(i(0), "or", i(0), Value.FALSE);
        // any nonzero is true, and the result is always 0 or 1
        check(i(5), "and", i(-2), Value.TRUE);
        check(i(0), "or", i(7), Value.TRUE);
    }

    @Test
    public void strings() {
        check(s("foo"), "+", s("bar"), s("foobar"));
        check(s("foo"), "==", s("foo"), Value.TRUE);
        check(s("foo"), "==", s("bar"), Value.FALSE);
        check(s("foo"), "!=", s("bar"), Value.TRUE);
        check(s("apple"), "<", s("banana"), Value.TRUE);
        check(s("banana"), "<", s("apple"), Value.FALSE);
        check(s("b"), ">=", s("b"), Value.TRUE);
        // case-sensitive: upper case sorts before lower case
        check(s("Zed"), "<", s("apple"), Value.TRUE);
    }

    @Test
    public void stringsDontSupportOtherOps() {
        typeError(s("a"), "-", s("b"));
        typeError(s("a"), "*", s("b"));
        typeError(s("a"), "/", s("b"));
        typeError(s("a"), "and", s("b"));
        typeError(s("a"), "or", s("b"));
    }

    @Test
    public void identifiers() {
        check(id("foo"), "==", id("foo"), Value.TRUE);
        check(id("foo"), "==", id("bar"), Value.FALSE);
        check(id("foo"), "!=", id("bar"), Value.TRUE);
        for (String op : new String[]{"+", "-", "*", "/", "<", ">", "<=", ">=", "and", "or"})
            typeError(id("a"), op, id("b"));
    }

    @Test
    public void mixedKindsAreTypeErrors() {
        for (String op : new String[]{"+", "-", "*", "/", "<", ">", "<=", ">=", "==", "!=", "and", "or"}) {
            typeError(i(1), op, s("1"));
            typeError(s("1"), op, i(1));
            typeError(s("foo"), op, id("foo"));
            typeError(id("foo"), op, i(1));
        }
    }

    @Test
    public void typeErrorMessage() {
        RuntimeException e = Assertions.assertThrows(RuntimeException.class, () -> i(1).binop("+", s("x")));
        Assertions.assertEquals("cannot add Int and Str", e.getMessage());
    }

    @Test
    public void unknownOperator() {
        RuntimeException e = Assertions.assertThrows(RuntimeException.class, () -> i(1).binop("%", i(2)));
        Assertions.assertEquals("unknown operator %", e.getMessage());
    }

    @Test
    public void unaryOps() {
        Assertions.assertEquals(i(-5), i(5).unop("-"));
        Assertions.assertEquals(i(5), i(-5).unop("-"));
        Assertions.assertEquals(i(0), i(0).unop("-"));
        Assertions.assertEquals(Value.TRUE, i(0).unop("not"));
        Assertions.assertEquals(Value.FALSE, i(1).unop("not"));
        // any nonzero is true, so not gives 0
        Assertions.assertEquals(Value.FALSE, i(-3).unop("not"));
    }

    @Test
    public void unaryTypeErrors() {
        for (Value v : new Value[]{s("a"), id("a")}) {
            for (String op : new String[]{"-", "not"}) {
                RuntimeException e = Assertions.assertThrows(RuntimeException.class, () -> v.unop(op),
                        op + " " + v);
                Assertions.assertTrue(e.getMessage().startsWith("cannot "), e.getMessage());
            }
        }
        RuntimeException e = Assertions.assertThrows(RuntimeException.class, () -> s("a").unop("-"));
        Assertions.assertEquals("cannot negate Str", e.getMessage());
        e = Assertions.assertThrows(RuntimeException.class, () -> i(1).unop("+"));
        Assertions.assertEquals("unknown operator +", e.getMessage());
    }

    @Test
    public void truthiness() {
        Assertions.assertFalse(i(0).isTrue());
        Assertions.assertTrue(i(1).isTrue());
        Assertions.assertTrue(i(-1).isTrue());
        Assertions.assertTrue(s("").isTrue());
        Assertions.assertTrue(id("x").isTrue());
    }
}
