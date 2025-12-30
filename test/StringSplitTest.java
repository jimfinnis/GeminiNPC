import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.pale.gemininpc.utils.TextUtils;

public class StringSplitTest {
    /**
     * Test that the string splitter words when the string is less than 256 characters long.
     */
    @Test
    public void shortStringTest(){
        String text = "This is a short string that is less than 256 characters long.";
        var chunks = TextUtils.splitIntoChunks(text);
        Assertions.assertEquals(1, chunks.size());
        Assertions.assertEquals(text, chunks.get(0), "Expected chunk to be the same as the original text.");
    }

    /**
     * Test that the string splitter works when the string is exactly 256 characters long.
     */
    @Test
    public void exact256CharsTest(){
        StringBuilder sb = new StringBuilder();
        int i=0;
        while (sb.length() < 256) {
            // fill with 'a' and a few spaces
            sb.append('a');
            if (sb.length() % 50 == 0 && sb.length() < 255) { // add a space every 50 characters, but not at the end
                sb.append(' ');
            }
        }
        Assertions.assertEquals(256, sb.length());
        String text = sb.toString();
        var chunks = TextUtils.splitIntoChunks(text);
        Assertions.assertEquals(1, chunks.size(), "Expected one chunk for exactly 256 characters.");
        Assertions.assertEquals(text, chunks.get(0), "Expected chunk to be the same as the original text.");
    }

    private static String generateString(int length, int repeats, String separator){
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < repeats; i++) {
            sb.append("a".repeat(length));
            if (i < repeats - 1) {
                sb.append(separator);
            }
        }
        return sb.toString();
    }

    @Test
    public void testStringGenerator(){
        String text = generateString(5, 5, " ");
        Assertions.assertEquals("aaaaa aaaaa aaaaa aaaaa aaaaa", text);
        text = generateString(10, 3, ", ");
        Assertions.assertEquals("aaaaaaaaaa, aaaaaaaaaa, aaaaaaaaaa", text);
    }

    /**
     * Test that the string splitter works when the string is longer than 256 characters and the break is not at a whitespace.
     */
    @Test
    public void longStringNoWhitespaceTest() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            sb.append('a'); // fill with 'a's
        }
        String text = sb.toString();
        var chunks = TextUtils.splitIntoChunks(text);
        Assertions.assertEquals(2, chunks.size(), "Expected two chunks for a string longer than 256 characters.");
        Assertions.assertEquals(text.substring(0, 256), chunks.get(0), "First chunk should be the first 256 characters.");
        Assertions.assertEquals(text.substring(256), chunks.get(1), "Second chunk should be the remaining characters.");
    }

    /**
     * Test that the string splitter works when the string is longer than 256 characters and the break is at a whitespace.
     */
    @Test
    public void longStringWithBreakAt256(){
        String s = generateString(7, 40, " "); // break will be at the end of the 256th character
        var chunks = TextUtils.splitIntoChunks(s);
        Assertions.assertEquals(2, chunks.size(), "Expected two chunks for a string longer than 256 characters with a break at whitespace.");
        Assertions.assertEquals(s.substring(0, 256).trim(), chunks.get(0), "First chunk should be the first 256 characters, space stripped.");
        Assertions.assertEquals(s.substring(256), chunks.get(1), "Second chunk should be the remaining characters.");
    }

    /**
     * Test that the string splitter works when the string is longer than 256 characters and the break is at a newline
     * after punctuation.
     */
    @Test
    public void longStringWithBreakAtNewlineTest() {
        String s = generateString(9, 25, " "); // 250 chars
        s += " foom.\nOnce upon a time.";
        var chunks = TextUtils.splitIntoChunks(s);
        Assertions.assertEquals(2, chunks.size());

        s = generateString(9, 25, " ") + " foom.";
        Assertions.assertEquals(s, chunks.get(0));
        Assertions.assertEquals("Once upon a time.", chunks.get(1));
    }
}
