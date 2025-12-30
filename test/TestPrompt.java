import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import org.pale.gemininpc.ai.Chat;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;

import java.nio.file.Paths;
import java.text.BreakIterator;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Scanner;

/**
 * Test class that expects a fully expanded prompt (i.e. no templating done) in the file PROMPT_FILE.
 * The console will accept input from the user.
 * This input will be inserted into a template which mirrors that sent in the actual plugin.
 * The first time round the loop (i.e. on creation of a new session) PROMPT_TEMPLATE_FIRST will be used,
 * and PROMPT_TEMPLATE_NOT_FIRST thereafter.
 */
public class TestPrompt {

    private final ChatModel model;
    private final Chat chat;

    private final String CONFIG = "x:\\plugins\\GeminiNPC\\config.yml";
    private final String MODEL = "gemini-2.0-flash-lite";
    private static final String PROMPT_FILE = "x:\\plugins\\GeminiNPC\\testprompt";
    private static final String PROMPT_TEMPLATE_FIRST = """
            {"context":{"time":"15:35","weather":"clear","nearbyPlayers":["jfinnis"],"light from the sun":"15/15",
            "light from lamps":"10/15","world":"world","recently seen":"no monsters",
            "recently heard":"no monsters","combat":"833 minutes ago","guarding player":"nothing","health":"maximum","inventory":["BONE"]},
            "input":"jfinnis: INSERTTEXTHERE"}
            """;
    private static final String PROMPT_TEMPLATE_NOT_FIRST = """
            {"context":{"time":"14:25"},"input:"jfinnis: INSERTTEXTHERE"}
            """;

    TestPrompt(){

        String apiKey = "";
        try {
            apiKey = extractApiKey(CONFIG);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        var b = GoogleAiGeminiChatModel.builder()
                .apiKey(apiKey)
                .modelName(MODEL)
                .logResponses(true)
                .timeout(Duration.ofSeconds(10))
                .responseFormat(ResponseFormat.JSON);

        model = b.build();
        String systemInstruction;
        try {
            List<String> lines = Files.readAllLines(Paths.get(PROMPT_FILE));
            systemInstruction = String.join("\n",lines);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        chat = Chat.builder()
                .maxMessages(30)
                .systemInstruction(systemInstruction)
                .build(model);
    }


    // first we need to extract the key. I'm going to do it the dumb way to avoid deps; it's the only
    // thing I need.

    public static String extractApiKey(String path) throws IOException {
        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("apikey:")) {
                    return line.substring("apikey:".length()).trim();
                }
            }
        }
        throw new RuntimeException("Can't find key in config file "+path);
    }

    /**
     * Wraps the given text into lines of maxWidth characters
     * without breaking words in the middle.
     */
    public static String wrapText(String text, int maxWidth, Locale locale) {
        if (text == null || text.isEmpty() || maxWidth <= 0) {
            return "";
        }

        BreakIterator boundary = BreakIterator.getLineInstance(locale);
        boundary.setText(text);

        StringBuilder result = new StringBuilder();
        int start = boundary.first();
        int lineLength = 0;

        for (int end = boundary.next(); end != BreakIterator.DONE; end = boundary.next()) {
            String word = text.substring(start, end);
            if (lineLength + word.length() > maxWidth) {
                result.append("\n");
                lineLength = 0;
            }
            result.append(word);
            lineLength += word.length();
            start = end;
        }

        return result.toString();
    }

    public static void main(String[] args){
        Scanner scanner = new Scanner(System.in);
        TestPrompt tp = new TestPrompt();
        boolean first = true;
        for(;;){
            System.out.print(">> ");
            String in = scanner.nextLine().trim();
            if(in.startsWith("reload")){
                tp = new TestPrompt();
                first = true;
                continue;
            }
            if(in.isBlank())continue;
            String tmp = first?PROMPT_TEMPLATE_FIRST:PROMPT_TEMPLATE_NOT_FIRST;
            first = false;
            in = tmp.replace("INSERTTEXTHERE", in).replaceAll("\\n","");
            System.out.println(in);
            Chat.Response r = tp.chat.sendAndGetResponse(in);
            System.out.println("Player:"+r.player);
            System.out.println("Text:"+wrapText(r.text, 100, Locale.ENGLISH));
            System.out.println("Action:"+r.action);

        }
    }
}
