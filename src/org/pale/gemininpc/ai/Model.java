package org.pale.gemininpc.ai;

import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.output.structured.Description;
import dev.langchain4j.service.AiServices;

/**
 * Encapsulates the model we're using
 */
public class Model {
    ChatModel model;

    public Model(String modelName, String apiKey){

        model = GoogleAiGeminiChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .responseFormat(ResponseFormat.JSON)
                .maxOutputTokens(200)
                .build();
    }
}
