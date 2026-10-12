package org.pale.gemininpc.quests;

/// An error in a quest file. As the exception passes up through the loader, each level adds where it was
/// (see `in`), so the final message reads like
///
///     participants.helga #2: when: expected a value but the expression ended
///
/// List items are numbered from 1, since that's how someone reading the YAML would count them.
public class QuestException extends RuntimeException {
    public QuestException(String message) {
        super(message);
    }

    public QuestException(String message, Throwable cause) {
        super(message, cause);
    }

    /// Run `body`, putting `where` in front of the message of anything it throws.
    public static void in(String where, Runnable body) {
        try {
            body.run();
        } catch (RuntimeException e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            throw new QuestException(where + ": " + msg, e);
        }
    }

    /// True if this error was raised deliberately by the loader, rather than being something unexpected
    /// (such as a NullPointerException) which probably means a bug and is worth a stack trace.
    public boolean isExpected() {
        Throwable t = this;
        while (t.getCause() != null)
            t = t.getCause();
        return t instanceof QuestException;
    }
}
