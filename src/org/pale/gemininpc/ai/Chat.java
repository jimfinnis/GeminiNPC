package org.pale.gemininpc.ai;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.input.Prompt;
import dev.langchain4j.model.output.structured.Description;
import dev.langchain4j.service.AiServices;
import org.pale.gemininpc.Plugin;

import java.util.List;


public class Chat {
    public static class ChatBuilder {
        private int maxMessages = 30;
        private String systemInst = "";
        public ChatBuilder maxMessages(int i) { maxMessages = 30; return this;}
        public ChatBuilder systemInstruction(String s){ systemInst = s; return this; }
        public Chat build(Model m){
            return new Chat(m, this);
        }
        private ChatBuilder(){} // avoid creating without "builder"
    }

    public static ChatBuilder builder() { return new ChatBuilder(); }

    public static class Response {
        public String player;
        public String response;
        public String command;

        public String toString(){
            return "Player:"+player+", Command:"+command+", Response:"+response;
        }
    }

    private interface Responder {
        Response respond(String text);
    }

    Responder responder;
    private MessageWindowChatMemory memory;

    private Chat(Model model, ChatBuilder b) {
        memory = MessageWindowChatMemory.withMaxMessages(b.maxMessages);
        responder = AiServices.builder(Responder.class)
                .chatMemory(memory)
                .chatModel(model.model)
                .build();
        memory.add(SystemMessage.from(b.systemInst));
    }

    public Response sendAndGetResponse(String msg){
        return responder.respond(msg);
    }

    public void dumpMem(){
        for(ChatMessage m: memory.messages()){
            Plugin.log("MESSAGE: "+m.toString());
        }
    }
}
