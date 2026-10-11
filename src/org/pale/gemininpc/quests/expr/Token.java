package org.pale.gemininpc.quests.expr;

/// A token produced by the `Lexer`. The kind of token is its class, so use pattern matching
/// (`t instanceof Token.OperatorToken o`, or a `switch`) to tell them apart.
///
/// Sealed, so a `switch` over a `Token` needs no `default`, and adding a new kind of token makes
/// any switch that doesn't handle it a compile error.
sealed abstract class   Token {
    public abstract String toString();

    static final class IntToken extends Token {
        int n;
        IntToken(int n){
            this.n = n;
        }
        public String toString(){
            return "INT:"+Integer.toString(n);
        }
    }

    static final class StringToken extends Token {
        String s;
        StringToken(String s){
            this.s = s;
        }
        public String toString(){
            return "STR:"+s;
        }
    }

    static final class IdentifierToken extends Token {
        String s;
        IdentifierToken(String s){
            this.s = s;
        }
        public String toString(){
            return "IDENT:"+s;
        }
    }

    static final class OperatorToken extends Token {
        String s;
        OperatorToken(String s){
            this.s = s;
        }
        public String toString(){
            return "OP:"+s;
        }
    }
}
