package org.pale.gemininpc.ai;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;

import java.time.Duration;

/**
 * Encapsulates the model we're using
 */
public class Model {
    ChatModel model;

    public Model(String modelName, String apiKey, int maxOutput){
        if(maxOutput<=0)
            maxOutput = 800;

        var b = GoogleAiGeminiChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .timeout(Duration.ofSeconds(5))
                .maxOutputTokens(maxOutput)
                .responseFormat(ResponseFormat.JSON);
        model = b.build();
    }
}
