package org.pale.gemininpc.quests.expr;

/// A value: a literal in the parse tree, the result of evaluating an expression, or an operand
/// to an operator.
///
/// Sealed, so a pattern-matching `switch` over a `Value` needs no `default`, and adding a new
/// kind of value makes any switch that doesn't handle it a compile error. Records give us
/// `equals` (same kind and same contents), `hashCode` and a readable `toString` for free.
///
/// There's no boolean type: booleans are `Int`s, 0 for false and 1 for true.
///
/// ## Operators
///
/// Each operator is a method here (`add`, `compare`, `neg`, ...), and `binop` and `unop` map an
/// operator string from the parse tree onto them. Both operands of a binary operator must be the
/// same kind of value. The default
/// implementations in this interface throw a type error, and each record overrides the ones it
/// supports, falling back to the default (via `Value.super`) when the other operand is the wrong
/// kind. What each kind supports:
///
/// - `Int`: everything. `/` is integer division; dividing by zero is an error. `and`/`or` treat
///   nonzero as true and give 0 or 1, as does `not`. Unary `-` negates.
/// - `Str`: `+` (concatenation), `==`/`!=`, and `<`/`>`/`<=`/`>=` (alphabetical, case-sensitive).
///   No unary operators.
/// - `Ident`: only `==`/`!=`.
///
/// `==` and `!=` with different kinds on each side are type errors, not false.
public sealed interface Value {
    record Int(int n) implements Value {
        public Value add(Value other) {
            if (other instanceof Int(int m)) return new Int(n + m);
            return Value.super.add(other);
        }

        public Value sub(Value other) {
            if (other instanceof Int(int m)) return new Int(n - m);
            return Value.super.sub(other);
        }

        public Value mul(Value other) {
            if (other instanceof Int(int m)) return new Int(n * m);
            return Value.super.mul(other);
        }

        public Value div(Value other) {
            if (other instanceof Int(int m)) {
                if (m == 0)
                    throw new RuntimeException("division by zero");
                return new Int(n / m);
            }
            return Value.super.div(other);
        }

        public int compare(String opname, Value other) {
            if (other instanceof Int(int m)) return Integer.compare(n, m);
            return Value.super.compare(opname, other);
        }

        public Value and(Value other) {
            if (other instanceof Int(int m)) return Value.of(n != 0 && m != 0);
            return Value.super.and(other);
        }

        public Value or(Value other) {
            if (other instanceof Int(int m)) return Value.of(n != 0 || m != 0);
            return Value.super.or(other);
        }

        public Value neg() {
            return new Int(-n);
        }

        public Value not() {
            return Value.of(n == 0);
        }
    }

    record Str(String s) implements Value {
        public Value add(Value other) {
            if (other instanceof Str(String t)) return new Str(s + t);
            return Value.super.add(other);
        }

        public int compare(String opname, Value other) {
            if (other instanceof Str(String t)) return s.compareTo(t);
            return Value.super.compare(opname, other);
        }
    }

    record Ident(String name) implements Value {}

    Int TRUE = new Int(1);
    Int FALSE = new Int(0);

    /// Convert a Java boolean into the `Int` that represents it.
    static Int of(boolean b) {
        return b ? TRUE : FALSE;
    }

    /// Whether this value counts as true in a condition: everything except `Int(0)`.
    default boolean isTrue() {
        return !(this instanceof Int(int n) && n == 0);
    }

    default void binopTypeError(String opname, Value other){
        throw new RuntimeException("cannot "+opname+" "+getClass().getSimpleName()+" and "+other.getClass().getSimpleName());
    }

    default void typeError(String opname){
        throw new RuntimeException("cannot "+opname+" "+getClass().getSimpleName());
    }

    /// Apply the binary operator `op` (as it appears in a `BinopNode`) with this value on the left
    /// and `other` on the right.
    default Value binop(String op, Value other) {
        return switch (op) {
            case "+" -> add(other);
            case "-" -> sub(other);
            case "*" -> mul(other);
            case "/" -> div(other);
            case "<" -> Value.of(compare("compare", other) < 0);
            case ">" -> Value.of(compare("compare", other) > 0);
            case "<=" -> Value.of(compare("compare", other) <= 0);
            case ">=" -> Value.of(compare("compare", other) >= 0);
            case "==" -> eq(other);
            case "!=" -> Value.of(!eq(other).isTrue());
            case "and" -> and(other);
            case "or" -> or(other);
            default -> throw new RuntimeException("unknown operator " + op);
        };
    }

    /// Apply the unary operator `op` (as it appears in a `UnopNode`) to this value.
    default Value unop(String op) {
        return switch (op) {
            case "-" -> neg();
            case "not" -> not();
            default -> throw new RuntimeException("unknown operator " + op);
        };
    }

    default Value neg() {
        typeError("negate");
        return null;
    }
    default Value not() {
        typeError("not");
        return null;
    }

    default Value add(Value other) {
        binopTypeError("add",other);
        return null;
    }
    default Value sub(Value other) {
        binopTypeError("subtract",other);
        return null;
    }
    default Value mul(Value other) {
        binopTypeError("multiply",other);
        return null;
    }
    default Value div(Value other){
        binopTypeError("divide",other);
        return null;
    }

    /// Order this value against `other`: negative, zero or positive as this is less than, equal to
    /// or greater than `other`. Used for all four ordering operators. `opname` is only for the
    /// error message.
    default int compare(String opname, Value other) {
        binopTypeError(opname,other);
        return 0;
    }

    /// Equality, which every kind of value supports as long as both sides are the same kind.
    default Value eq(Value other) {
        if (getClass() != other.getClass())
            binopTypeError("compare",other);
        return Value.of(equals(other));
    }

    default Value and(Value other) {
        binopTypeError("and",other);
        return null;
    }
    default Value or(Value other) {
        binopTypeError("or",other);
        return null;
    }
}
