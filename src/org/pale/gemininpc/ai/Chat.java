package org.pale.gemininpc.ai;

import com.google.gson.JsonObject;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import org.pale.gemininpc.Plugin;

import java.util.*;

import static org.pale.gemininpc.utils.Json.getDifferences;

/// An NPC's link to the LLM. The NPC keeps a separate conversation, with its own memory, for each player
/// it talks to (its "partner" for that exchange), so that what it tells one player can't reach another
/// who wasn't there. Players within earshot of an exchange get it added to their own conversation too, so
/// a player's memory of the NPC holds everything they actually heard.
///
/// `respond` is called from a background thread, because the LLM call blocks. Each conversation is locked
/// while it's in use, and a thread never holds one conversation's lock while taking another's, so requests
/// for different players can run at once without deadlocking.
public class Chat {
    public static class ChatBuilder {
        private int maxMessages = 30;
        private String systemInst = "";
        public ChatBuilder maxMessages(int i) { maxMessages = i; return this;}
        public ChatBuilder systemInstruction(String s){ systemInst = s; return this; }
        public Chat build(ChatModel m){
            return new Chat(m, this);
        }
        private ChatBuilder(){} // avoid creating without "builder"
    }

    public static ChatBuilder builder() { return new ChatBuilder(); }

    public static class Response {
        public String player;
        public String text;
        public String action;

        public String toString(){
            return "Player:"+player+", Action:"+action+", Msg:"+text;
        }
    }

    private interface Responder {
        Response respond(String text);
    }

    /// A conversation is dropped after this long without being used...
    static final long CONVERSATION_TIMEOUT_MS = 30 * 60 * 1000;
    /// ...and at most this many are kept, the least recently used being dropped first.
    static final int MAX_CONVERSATIONS = 20;

    /// The NPC's conversation with one player.
    private class Conversation {
        final String playerName;
        final MessageWindowChatMemory memory;
        final Responder responder;
        JsonObject prevContext = null;  // the last full context sent, so only changes need sending
        long lastUsed = System.currentTimeMillis();

        Conversation(String playerName){
            this.playerName = playerName;
            memory = MessageWindowChatMemory.withMaxMessages(maxMessages);
            memory.add(SystemMessage.from(systemInst));
            responder = AiServices.builder(Responder.class)
                    .chatMemory(memory)
                    .chatModel(model)
                    .build();
        }
    }

    /// Someone who heard an exchange.
    public record Listener(UUID id, String name) {}

    /// Everything needed for one exchange, gathered on the main thread before the request's thread starts.
    /// @param partner who the NPC is talking to
    /// @param context the full context; only the parts that have changed since this partner's last
    ///                exchange are sent
    /// @param input what was said or happened, e.g. "jfinnis: hello" or "event: (you look around)"
    /// @param earshot other real players near enough to hear, who get the exchange in their memory too
    public record Request(Listener partner, JsonObject context, String input, List<Listener> earshot) {}

    private final ChatModel model;
    private final int maxMessages;
    private final String systemInst;
    private final Map<UUID, Conversation> conversations = new LinkedHashMap<>();

    private Chat(ChatModel model, ChatBuilder b) {
        this.model = model;
        this.maxMessages = b.maxMessages;
        this.systemInst = b.systemInst;
    }

    /// Get the conversation with a player, starting one if needed, and drop any which are stale.
    private synchronized Conversation conversation(Listener who){
        long now = System.currentTimeMillis();
        conversations.values().removeIf(c -> now - c.lastUsed > CONVERSATION_TIMEOUT_MS);
        Conversation c = conversations.get(who.id());
        if(c==null){
            c = new Conversation(who.name());
            conversations.put(who.id(), c);
            // drop the least recently used if there are too many
            while(conversations.size() > MAX_CONVERSATIONS){
                UUID oldest = Collections.min(conversations.entrySet(),
                        Comparator.comparingLong(e -> e.getValue().lastUsed)).getKey();
                conversations.remove(oldest);
            }
        }
        c.lastUsed = now;
        return c;
    }

    /// Send an exchange to the LLM in the partner's conversation, and add it to the conversations of those
    /// in earshot. Blocks until the LLM replies, so call it from a background thread. Returns null if there
    /// was no reply.
    public Response respond(Request req){
        Conversation c = conversation(req.partner());
        Response r;
        synchronized (c) {
            JsonObject msg = new JsonObject();
            msg.add("context", getDifferences(c.prevContext, req.context()));
            msg.addProperty("input", req.input());
            String outString = msg.toString();
            Plugin.log("Sending to AI (talking to " + req.partner().name() + "): " + outString);
            r = c.responder.respond(outString);
            // Only count the context as seen once there's a reply. If the call fails (e.g. times out), the
            // model may never have seen it, so the changes are sent again next time rather than lost.
            c.prevContext = req.context();
        }
        if(r!=null) {
            // Those in earshot hear what was said and the reply, but not the context or any action: the
            // context is the partner's, and later will hold their quest prompts.
            JsonObject heard = new JsonObject();
            heard.addProperty("input", req.input());
            JsonObject reply = new JsonObject();
            reply.addProperty("player", r.player);
            reply.addProperty("text", r.text);
            for (Listener l : req.earshot()) {
                Conversation other = conversation(l);
                synchronized (other) {
                    other.memory.add(UserMessage.from(heard.toString()));
                    if (r.text != null)
                        other.memory.add(AiMessage.from(reply.toString()));
                }
            }
        }
        return r;
    }

    public void dumpMem(){
        List<Conversation> all;
        synchronized (this) {
            all = new ArrayList<>(conversations.values());
        }
        if(all.isEmpty())
            Plugin.log("No conversations");
        for(Conversation c: all){
            Plugin.log("CONVERSATION WITH " + c.playerName + ":");
            synchronized (c) {
                for (ChatMessage m : c.memory.messages())
                    Plugin.log("MESSAGE: " + m.toString());
            }
        }
    }
}
