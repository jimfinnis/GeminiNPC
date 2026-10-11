package org.pale.gemininpc.quests.expr;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

// In the same package as Lexer because the lexer and token classes are package-private.
public class LexerTest {
    // Lex the whole input, turning each token into its toString() (e.g. "IDENT:foo") so whole
    // token streams can be compared as lists of strings.
    private static List<String> lex(String input) {
        Lexer l = new Lexer(input);
        List<String> out = new ArrayList<>();
        while (l.hasNext())
            out.add(l.next().toString());
        return out;
    }

    private static void check(String input, String... expected) {
        Assertions.assertEquals(List.of(expected), lex(input), "Input: " + input);
    }

    @Test
    public void empty() {
        check("");
        check("   \t\n ");
    }

    @Test
    public void integers() {
        check("42", "INT:42");
        check("0 007", "INT:0", "INT:7");
        // no unary minus in the lexer
        check("-1", "OP:-", "INT:1");
    }

    @Test
    public void identifiers() {
        check("foo", "IDENT:foo");
        check("met_king2", "IDENT:met_king2");
        check("_x", "IDENT:_x");
        check("foo bar", "IDENT:foo", "IDENT:bar");
        // a leading digit starts an integer, which stops at the first letter
        check("123abc", "INT:123", "IDENT:abc");
    }

    @Test
    public void strings() {
        check("\"hello world\"", "STR:hello world");
        check("'hello'", "STR:hello");
        check("\"\"", "STR:");
        // the other kind of quote is just a character inside a string
        check("\"it's\"", "STR:it's");
        check("'say \"hi\"'", "STR:say \"hi\"");
        // strings don't need whitespace around them
        check("a\"b\"c", "IDENT:a", "STR:b", "IDENT:c");
    }

    @Test
    public void escapes() {
        check("\"a \\\"b\\\"\"", "STR:a \"b\"");
        check("'it\\'s'", "STR:it's");
        check("\"back\\\\slash\"", "STR:back\\slash");
        // escaped characters are taken literally
        check("\"\\n\"", "STR:n");
    }

    @Test
    public void unterminatedStrings() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> new Lexer("\"abc"));
        Assertions.assertThrows(IllegalArgumentException.class, () -> new Lexer("'abc\""));
        Assertions.assertThrows(IllegalArgumentException.class, () -> new Lexer("\"abc\\\""));
        Assertions.assertThrows(IllegalArgumentException.class, () -> new Lexer("\"abc\\"));
    }

    @Test
    public void singleCharOperators() {
        check("+-*/", "OP:+", "OP:-", "OP:*", "OP:/");
        check("(a)", "OP:(", "IDENT:a", "OP:)");
        check("((", "OP:(", "OP:(");
        check("a<b", "IDENT:a", "OP:<", "IDENT:b");
    }

    @Test
    public void multiCharOperators() {
        check("== != <= >=",
                "OP:==", "OP:!=", "OP:<=", "OP:>=");
        check("a==b", "IDENT:a", "OP:==", "IDENT:b");
        // a multi-char operator is taken first, then whatever follows
        check("===", "OP:==", "OP:=");
        // && and || aren't operators (use and/or), so they lex as two single chars each
        check("&& ||", "OP:&", "OP:&", "OP:|", "OP:|");
        check("==-1", "OP:==", "OP:-", "INT:1");
        check("!(", "OP:!", "OP:(");
        // whitespace splits what would otherwise be a multi-char operator
        check("= =", "OP:=", "OP:=");
    }

    @Test
    public void wordOperators() {
        check("a and b or c", "IDENT:a", "OP:and", "IDENT:b", "OP:or", "IDENT:c");
        check("not a", "OP:not", "IDENT:a");
        // only whole words count - these are just identifiers that start with an operator word
        check("android order nothing", "IDENT:android", "IDENT:order", "IDENT:nothing");
        // word operators are case-sensitive
        check("AND", "IDENT:AND");
    }

    @Test
    public void fullExpression() {
        check("met_king >= 2 and !(name != \"Bob\" or gold < -10)",
                "IDENT:met_king", "OP:>=", "INT:2", "OP:and",
                "OP:!", "OP:(", "IDENT:name", "OP:!=", "STR:Bob",
                "OP:or", "IDENT:gold", "OP:<", "OP:-", "INT:10", "OP:)");
    }

    @Test
    public void hasNextAndNext() {
        Lexer l = new Lexer("a 1");
        Assertions.assertTrue(l.hasNext());
        Assertions.assertEquals("IDENT:a", l.next().toString());
        Assertions.assertTrue(l.hasNext());
        Assertions.assertEquals("INT:1", l.next().toString());
        Assertions.assertFalse(l.hasNext());
        Assertions.assertThrows(IndexOutOfBoundsException.class, l::next);
    }

    @Test
    public void peek() {
        Lexer l = new Lexer("a 1");
        // peek returns the next token without advancing, so it can be repeated
        Assertions.assertEquals("IDENT:a", l.peek().toString());
        Assertions.assertEquals("IDENT:a", l.peek().toString());
        Assertions.assertEquals("IDENT:a", l.next().toString());
        Assertions.assertEquals("INT:1", l.peek().toString());
        Assertions.assertEquals("INT:1", l.next().toString());
        Assertions.assertThrows(IndexOutOfBoundsException.class, l::peek);
    }

    // toString() is only for display - check the fields the parser will actually read hold the
    // bare values, without any type prefix.
    @Test
    public void tokenFields() {
        Lexer l = new Lexer("foo 42 \"bar\" ==");
        Assertions.assertEquals("foo", Assertions.assertInstanceOf(Token.IdentifierToken.class, l.next()).s);
        Assertions.assertEquals(42, Assertions.assertInstanceOf(Token.IntToken.class, l.next()).n);
        Assertions.assertEquals("bar", Assertions.assertInstanceOf(Token.StringToken.class, l.next()).s);
        Assertions.assertEquals("==", Assertions.assertInstanceOf(Token.OperatorToken.class, l.next()).s);
    }
}
