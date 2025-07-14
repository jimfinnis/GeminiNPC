package org.pale.gemininpc.utils;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;

import java.util.*;

public class TextUtils {

    public static ItemStack writeBook(String title, String author, String text) {
        ItemStack st = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) st.getItemMeta();
        if (meta != null) {
            meta.setAuthor(author);
            meta.setTitle(title);
            var pages = TextUtils.splitIntoChunks(text);
            meta.setPages(pages);
            st.setItemMeta(meta);
        } else {
            throw new RuntimeException("Could not create book meta");
        }
        return st;
    }


    public static List<String> splitIntoChunks(String text) {
        List<String> chunks = new ArrayList<>();
        int maxChunkSize = 256;
        int start = 0;

        while (start < text.length()) {
            int end = Math.min(start + maxChunkSize, text.length());

            // Try to break at the last whitespace character (space, tab, newline)
            if (end < text.length()) {
                int lastWhitespace = -1;
                for (int i = end; i > start; i--) {
                    char c = text.charAt(i - 1);
                    if (Character.isWhitespace(c)) {
                        lastWhitespace = i;
                        break;
                    }
                }
                if (lastWhitespace > start) {
                    end = lastWhitespace;
                }
            }

            String chunk = text.substring(start, end);
            chunks.add(chunk.trim());

            start = end;

            // Skip any whitespace at the start of the next chunk
            while (start < text.length() && Character.isWhitespace(text.charAt(start)) && text.charAt(start) != '\n') {
                start++;
            }
        }

        return chunks;
    }

}
