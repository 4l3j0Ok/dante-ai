package dev.danteai.test.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.danteai.llm.AnthropicProvider;
import dev.danteai.llm.LlmException;
import dev.danteai.llm.LlmMessage;
import dev.danteai.llm.LlmRequest;
import dev.danteai.llm.LlmResponse;
import dev.danteai.llm.ToolCall;
import dev.danteai.llm.ToolSpec;
import dev.danteai.test.MockHttpServer;
import dev.danteai.test.MockHttpServer.Reply;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnthropicProviderTest {
	private static AnthropicProvider provider(MockHttpServer server, String key) {
		return new AnthropicProvider(new AnthropicProvider.Settings(server.baseUrl(), key, "claude-haiku-4-5", "2023-06-01",
			Duration.ofSeconds(5), 1));
	}

	private static ToolSpec tool(String name) {
		JsonObject schema = JsonParser.parseString("{\"type\":\"object\",\"properties\":{\"player\":{\"type\":\"string\"}},\"required\":[\"player\"]}")
			.getAsJsonObject();
		return new ToolSpec(name, "desc", schema);
	}

	@Test
	void usesNativeMessagesProtocol() throws Exception {
		try (MockHttpServer server = new MockHttpServer()) {
			server.enqueue(Reply.json(200, """
				{"id":"msg_1","type":"message","role":"assistant","content":[
				  {"type":"text","text":"¡Vamos!"},
				  {"type":"tool_use","id":"toolu_1","name":"follow_player","input":{"player":"Alejo"}}
				],"stop_reason":"tool_use","usage":{"input_tokens":30,"output_tokens":9}}"""));
			JsonObject args = new JsonObject();
			args.addProperty("player", "Alejo");
			List<LlmMessage> history = List.of(
				new LlmMessage.Assistant("Hola de antes"),
				new LlmMessage.User("vení conmigo"),
				new LlmMessage.Assistant("Voy", List.of(new ToolCall("toolu_0", "look_at_player", args))),
				new LlmMessage.ToolResults(List.of(new LlmMessage.ToolResult("toolu_0", "look_at_player", "no lo ves", true))),
				new LlmMessage.User("dale"));
			LlmResponse resp = provider(server, "sk-ant-test")
				.complete(new LlmRequest("Sos Dante", history, List.of(tool("follow_player")), 0.9, 300)).get(5, TimeUnit.SECONDS);

			assertEquals("¡Vamos!", resp.text());
			assertEquals("toolu_1", resp.toolCalls().getFirst().id());
			assertEquals("Alejo", resp.toolCalls().getFirst().arguments().get("player").getAsString());
			assertEquals("tool_use", resp.stopReason());

			MockHttpServer.Recorded r = server.requests().getFirst();
			assertEquals("/v1/messages", r.path());
			assertEquals("sk-ant-test", r.header("x-api-key"));
			assertEquals("2023-06-01", r.header("anthropic-version"));
			JsonObject body = JsonParser.parseString(r.bodyText()).getAsJsonObject();
			assertEquals("Sos Dante", body.get("system").getAsString());
			assertEquals(300, body.get("max_tokens").getAsInt());
			assertEquals("follow_player", body.getAsJsonArray("tools").get(0).getAsJsonObject().get("name").getAsString());
			assertTrue(body.getAsJsonArray("tools").get(0).getAsJsonObject().has("input_schema"));
			assertEquals("auto", body.getAsJsonObject("tool_choice").get("type").getAsString());

			JsonArray messages = body.getAsJsonArray("messages");
			// Empieza con usuario, alterna roles y los resultados van como tool_result dentro de un mensaje user.
			assertEquals("user", messages.get(0).getAsJsonObject().get("role").getAsString());
			for (int i = 1; i < messages.size(); i++) {
				assertFalse(messages.get(i).getAsJsonObject().get("role").getAsString()
					.equals(messages.get(i - 1).getAsJsonObject().get("role").getAsString()), "roles alternados");
			}
			JsonObject assistantWithTool = messages.get(3).getAsJsonObject();
			assertEquals("assistant", assistantWithTool.get("role").getAsString());
			assertEquals("tool_use", assistantWithTool.getAsJsonArray("content").get(1).getAsJsonObject().get("type").getAsString());
			JsonObject toolResultMsg = messages.get(4).getAsJsonObject();
			JsonObject result = toolResultMsg.getAsJsonArray("content").get(0).getAsJsonObject();
			assertEquals("tool_result", result.get("type").getAsString());
			assertEquals("toolu_0", result.get("tool_use_id").getAsString());
			assertTrue(result.get("is_error").getAsBoolean());
			// El "dale" posterior se fusiona en el mismo mensaje de usuario que el tool_result.
			assertEquals("text", toolResultMsg.getAsJsonArray("content").get(1).getAsJsonObject().get("type").getAsString());
		}
	}

	@Test
	void missingKeyFailsFastWithoutNetwork() throws Exception {
		try (MockHttpServer server = new MockHttpServer()) {
			CompletionException e = assertThrows(CompletionException.class,
				() -> provider(server, "").complete(new LlmRequest("s", List.of(new LlmMessage.User("x")), List.of(), null, 10)).join());
			assertInstanceOf(LlmException.class, e.getCause());
			assertEquals(0, server.requests().size());
		}
	}

	@Test
	void retriesOverloadedThenSucceeds() throws Exception {
		try (MockHttpServer server = new MockHttpServer()) {
			server.enqueue(Reply.json(529, "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\"}}"));
			server.enqueue(Reply.json(200, "{\"content\":[{\"type\":\"text\",\"text\":\"Guau\"}],\"stop_reason\":\"end_turn\"}"));
			LlmResponse r = provider(server, "k").complete(new LlmRequest("s", List.of(new LlmMessage.User("x")), List.of(), null, 10))
				.get(10, TimeUnit.SECONDS);
			assertEquals("Guau", r.text());
			assertEquals(2, server.requests().size());
		}
	}
}
