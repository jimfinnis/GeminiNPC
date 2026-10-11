package org.pale.gemininpc.quests.expr;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/// Splits an expression string into tokens for the parser.
///
/// The whole string is tokenised up front in the constructor; `hasNext()` and `next()`
/// then just walk the resulting list. Token rules:
///
/// - Whitespace separates tokens and is otherwise ignored.
/// - A run of digits is an integer, e.g. `42`. There is no unary minus here: `-1` lexes
///   as the operator `-` followed by the integer `1`, and it's up to the parser to handle that.
/// - A letter or underscore followed by letters, digits and underscores is an identifier,
///   e.g. `met_king2`.
/// - Text in single or double quotes is a string. It ends at the next quote of the same kind, so
///   `"it's"` works. A backslash makes the following character literal, so `"a \"b\""`
///   gives `a "b"`.
/// - Anything else is an operator. Operators are single characters, except for those in
///   `MULTI_CHAR_OPS`, which are matched in preference.
///
/// An unterminated string throws `IllegalArgumentException`.
public class Lexer {
    /// Operators longer than one character. These are checked in order and the first match wins, so if
    /// one operator is a prefix of another (e.g. `==` and `===`), the longer must come first.
    private static final String[] MULTI_CHAR_OPS = {
            "==", "!=", "<=", ">="
    };
    private static final Set<String> WORD_OPS = Set.of(
            "and", "or", "not"
    );

    private final List<Token> tokens = new ArrayList<>();
    // index of the next token to be returned by next()
    private int pos = 0;

    Lexer(String s){
        int i = 0;      // current position in s
        while (i < s.length()) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (Character.isDigit(c)) {
                // integer: consume all the digits
                int start = i;
                while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
                tokens.add(new Token.IntToken(Integer.parseInt(s.substring(start, i))));
            } else if (Character.isLetter(c) || c == '_') {
                // identifier: consume letters, digits and underscores. Digits are only allowed after
                // the first character, because a leading digit is caught by the integer case above.
                int start = i;
                while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_')) i++;
                String op = s.substring(start, i);
                // handle operators which are words
                if (WORD_OPS.contains(op))
                    tokens.add(new Token.OperatorToken(op));
                else
                    tokens.add(new Token.IdentifierToken(op));
            } else if (c == '"' || c == '\'') {
                // string literal, terminated by the same quote it started with (c).
                // Backslash escapes the next char, which is added literally (so \n is just "n").
                StringBuilder sb = new StringBuilder();
                i++;    // skip opening quote
                while (true) {
                    if (i >= s.length())
                        throw new IllegalArgumentException("unterminated string in: " + s);
                    char d = s.charAt(i++);
                    if (d == c) break;  // closing quote, already consumed
                    if (d == '\\') {
                        // a trailing backslash means there's nothing to escape and no closing quote
                        if (i >= s.length())
                            throw new IllegalArgumentException("unterminated string in: " + s);
                        d = s.charAt(i++);
                    }
                    sb.append(d);
                }
                tokens.add(new Token.StringToken(sb.toString()));
            } else {
                // any other character is an operator - use a multi-char operator if one starts here,
                // otherwise just this single char
                String op = String.valueOf(c);
                for (String m : MULTI_CHAR_OPS) {
                    if (s.startsWith(m, i)) {
                        op = m;
                        break;
                    }
                }
                tokens.add(new Token.OperatorToken(op));
                i += op.length();
            }
        }
    }

    /// Returns true if there are tokens left to read.
    boolean hasNext(){
        return pos < tokens.size();
    }

    /// Returns the next token, advancing past it.
    ///
    /// Throws `IndexOutOfBoundsException` if there are no tokens left - check `hasNext()` first.
    Token next(){
        return tokens.get(pos++);
    }

    /// Peek operator - gets the current token if there is one, does not advance.
    Token peek(){
        return tokens.get(pos);
    }

}
