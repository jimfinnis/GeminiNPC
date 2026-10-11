import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.pale.gemininpc.utils.TextUtils;

import java.util.List;

public class QuotedSplitTest {
    private static void check(String input, String... expected) {
        Assertions.assertEquals(List.of(expected), TextUtils.splitQuoted(input), "Input: " + input);
    }

    @Test
    public void plainWords() {
        check("foo bar baz", "foo", "bar", "baz");
        check("  foo   bar\tbaz  ", "foo", "bar", "baz");
        check("");
        check("   ");
    }

    @Test
    public void quotedWords() {
        check("foo \"bar baz\" quux", "foo", "bar baz", "quux");
        check("\"bar baz\"", "bar baz");
        check("\"  spaces  kept  \"", "  spaces  kept  ");
    }

    @Test
    public void quotesMidWord() {
        check("a\"b c\"d", "ab cd");
        check("key=\"some value\"", "key=some value");
    }

    @Test
    public void emptyQuotes() {
        check("foo \"\" bar", "foo", "", "bar");
        check("\"\"", "");
    }

    @Test
    public void escapes() {
        check("say \"he said \\\"hi\\\"\"", "say", "he said \"hi\"");
        check("a\\ b c", "a b", "c");
        check("trailing\\", "trailing\\");
    }

    @Test
    public void unterminatedQuote() {
        check("foo \"bar baz", "foo", "bar baz");
    }

    @Test
    public void apostrophesNotSpecial() {
        check("don't stop", "don't", "stop");
    }
}