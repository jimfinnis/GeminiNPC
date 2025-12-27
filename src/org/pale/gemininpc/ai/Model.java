package org.pale.gemininpc.ai;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import org.bukkit.configuration.ConfigurationSection;
import org.pale.gemininpc.Plugin;

import java.time.Duration;

/**
 * Encapsulates the model we're using
 */
public class Model {
    ChatModel model;
    String info;

    public Model(ConfigurationSection modelSection){
        String modelName = modelSection.getString("model", "gemini-2.0-flash-lite");
        int maxOutput = modelSection.getInt("max-output-tokens", 0);

        StringBuilder sb = new StringBuilder();

        if (modelName.contains("gemini")) {
            String apiKey = modelSection.getString("apikey", "NOKEY");
            int timeout = modelSection.getInt("timeout", 10);
            sb.append("Cloud Gemini model: ").append(modelName).append(", maxOutputTokens :").append(maxOutput);
            var b = GoogleAiGeminiChatModel.builder()
                    .apiKey(apiKey)
                    .modelName(modelName)
                    .timeout(Duration.ofSeconds(timeout))
                    .responseFormat(ResponseFormat.JSON);
            if(maxOutput>0)
                b.maxOutputTokens(maxOutput);
            model = b.build();
        } else if(modelName.contains("gemma")) {
            String baseUrl = modelSection.getString("baseUrl", "http://localhost:11434");
            sb.append("Local Ollama model: ").append(modelName).append(", URL: ").append(baseUrl);
            var b = OllamaChatModel.builder()
                    .modelName(modelName)
                    .baseUrl(baseUrl);
            model = b.build();
        }
        info = sb.toString();
    }

    public String toString(){
        return info;
    }
}
