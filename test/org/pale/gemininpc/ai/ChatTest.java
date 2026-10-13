package org.pale.gemininpc.ai;

import com.google.gson.JsonObject;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/// An NPC's conversations: one per player, each with its own memory and context diff, with exchanges
/// shared with those in earshot. A fake model records exactly what each request contained.
public class ChatTest {
    /// Stands in for the LLM: records the messages of each request, and replies with fixed JSON.
    static class FakeModel implements ChatModel {
        final List<List<ChatMessage>> requests = new ArrayList<>();
        boolean failNext = false;   // set to make the next call fail, as a timeout would

        @Override
        public ChatResponse doChat(ChatRequest request) {
            if (failNext) {
                failNext = false;
                throw new RuntimeException("timed out");
            }
            requests.add(List.copyOf(request.messages()));
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from("{\"player\":\"someone\",\"text\":\"reply " + requests.size()
                            + "\",\"action\":\"secret_action\"}"))
                    .build();
        }

        /// The text of every message in the last request, joined, to search for things that shouldn't be there.
        String lastRequestText() {
            return requests.getLast().stream().map(ChatTest::text).collect(Collectors.joining("\n"));
        }
    }

    static String text(ChatMessage m) {
        return switch (m) {
            case UserMessage u -> u.singleText();
            case AiMessage a -> a.text();
            case SystemMessage s -> s.text();
            default -> m.toString();
        };
    }

    final FakeModel model = new FakeModel();
    final Chat chat = Chat.builder().systemInstruction("You are Prunesquallor.").build(model);
    final Chat.Listener alice = new Chat.Listener(UUID.randomUUID(), "Alice");
    final Chat.Listener bob = new Chat.Listener(UUID.randomUUID(), "Bob");

    static JsonObject context(String time, String secret) {
        JsonObject c = new JsonObject();
        c.addProperty("time", time);
        if (secret != null)
            c.addProperty("quest", secret);
        return c;
    }

    @Test
    void replyIsParsed() {
        Chat.Response r = chat.respond(new Chat.Request(alice, context("10:00", null), "Alice: hello", List.of()));
        assertEquals("reply 1", r.text);
        assertEquals("secret_action", r.action);
    }

    @Test
    void eachPlayerHasTheirOwnMemory() {
        chat.respond(new Chat.Request(alice, context("10:00", null), "Alice: my name is Alice", List.of()));
        chat.respond(new Chat.Request(bob, context("10:00", null), "Bob: hello", List.of()));
        // Bob wasn't there, so his conversation starts with just the system message
        String bobs = model.lastRequestText();
        assertTrue(bobs.contains("You are Prunesquallor."));
        assertFalse(bobs.contains("my name is Alice"), bobs);
    }

    @Test
    void contextIsDiffedPerPlayer() {
        chat.respond(new Chat.Request(alice, context("10:00", null), "Alice: one", List.of()));
        chat.respond(new Chat.Request(alice, context("10:00", null), "Alice: two", List.of()));
        // nothing changed, so Alice's second request sends an empty context
        assertTrue(text(model.requests.getLast().getLast()).contains("\"context\":{}"),
                text(model.requests.getLast().getLast()));
        // Bob hasn't seen any context yet, so he gets all of it
        chat.respond(new Chat.Request(bob, context("10:00", null), "Bob: hello", List.of()));
        assertTrue(text(model.requests.getLast().getLast()).contains("\"time\":\"10:00\""));
    }

    @Test
    void contextIsResentAfterAFailedCall() {
        chat.respond(new Chat.Request(alice, context("10:00", null), "Alice: one", List.of()));
        model.failNext = true;
        assertThrows(RuntimeException.class, () ->
                chat.respond(new Chat.Request(alice, context("10:00", "new guidance"), "Alice: two", List.of())));
        chat.respond(new Chat.Request(alice, context("10:00", "new guidance"), "Alice: three", List.of()));
        String last = text(model.requests.getLast().getLast());
        assertTrue(last.contains("new guidance"), last);
    }

    @Test
    void earshotHearsTheWordsButNotTheContextOrAction() {
        chat.respond(new Chat.Request(alice, context("10:00", "Angren is Lofgar"),
                "Alice: who is Angren?", List.of(bob)));
        chat.respond(new Chat.Request(bob, context("10:00", null), "Bob: what did you say?", List.of()));
        String bobs = model.lastRequestText();
        assertTrue(bobs.contains("Alice: who is Angren?"), bobs);   // he heard the question...
        assertTrue(bobs.contains("reply 1"), bobs);                  // ...and the reply
        assertFalse(bobs.contains("Angren is Lofgar"), bobs);        // but not Alice's context
        assertFalse(bobs.contains("secret_action"), bobs);           // or the action
    }

    @Test
    void someoneNotInEarshotHearsNothing() {
        Chat.Listener carol = new Chat.Listener(UUID.randomUUID(), "Carol");
        chat.respond(new Chat.Request(alice, context("10:00", null), "Alice: a secret", List.of(bob)));
        chat.respond(new Chat.Request(carol, context("10:00", null), "Carol: hi", List.of()));
        assertFalse(model.lastRequestText().contains("a secret"));
    }

    @Test
    void leastRecentlyUsedConversationIsDropped() throws InterruptedException {
        chat.respond(new Chat.Request(alice, context("10:00", null), "Alice: remember this", List.of()));
        for (int i = 0; i < Chat.MAX_CONVERSATIONS; i++) {
            Thread.sleep(1);    // so that each conversation was last used at a different time
            Chat.Listener other = new Chat.Listener(UUID.randomUUID(), "P" + i);
            chat.respond(new Chat.Request(other, context("10:00", null), "P" + i + ": hi", List.of()));
        }
        // Alice's was the least recently used, so it's gone and she starts again
        chat.respond(new Chat.Request(alice, context("10:00", null), "Alice: do you remember?", List.of()));
        String alices = model.lastRequestText();
        assertFalse(alices.contains("remember this"), alices);
        assertTrue(alices.contains("\"time\":\"10:00\""), "full context again: " + alices);
    }
}
