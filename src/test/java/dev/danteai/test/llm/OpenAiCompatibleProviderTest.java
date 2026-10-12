package dev.danteai.test.llm;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.danteai.llm.LlmException;
import dev.danteai.llm.LlmMessage;
import dev.danteai.llm.LlmRequest;
import dev.danteai.llm.LlmResponse;
import dev.danteai.llm.OpenAiCompatibleProvider;
import dev.danteai.llm.ToolCall;
import dev.danteai.llm.ToolSpec;
import dev.danteai.test.MockHttpServer;
import dev.danteai.test.MockHttpServer.Reply;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiCompatibleProviderTest {
	private static OpenAiCompatibleProvider provider(MockHttpServer server, String key, boolean json, int retries) {
		return new OpenAiCompatibleProvider(new OpenAiCompatibleProvider.Settings(server.baseUrl() + "/v1/", key, "local-model",
			"max_tokens", json, Map.of("X-Extra", "1"), Duration.ofSeconds(5), retries));
	}

	private static ToolSpec sitTool() {
		JsonObject schema = JsonParser.parseString("{\"type\":\"object\",\"properties\":{},\"required\":[]}").getAsJsonObject();
		return new ToolSpec("sit", "Sentarse", schema);
	}

	@Test
	void buildsChatCompletionRequestWithToolsAndParsesToolCalls() throws Exception {
		try (MockHttpServer server = new MockHttpServer()) {
			server.enqueue(Reply.json(200, """
				{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
				  {"id":"call_1","type":"function","function":{"name":"follow_player","arguments":"{\\"player\\":\\"Alejo\\"}"}}
				]},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":12,"completion_tokens":3}}"""));
			LlmRequest req = new LlmRequest("Sos Dante", List.of(new LlmMessage.User("vení conmigo")), List.of(sitTool()), 0.7, 200);
			LlmResponse resp = provider(server, "sk-test", false, 0).complete(req).get(5, TimeUnit.SECONDS);

			assertEquals(1, resp.toolCalls().size());
			ToolCall call = resp.toolCalls().getFirst();
			assertEquals("call_1", call.id());
			assertEquals("follow_player", call.name());
			assertEquals("Alejo", call.arguments().get("player").getAsString());
			assertEquals(12, resp.inputTokens());

			MockHttpServer.Recorded r = server.requests().getFirst();
			assertEquals("/v1/chat/completions", r.path());
			assertEquals("Bearer sk-test", r.header("Authorization"));
			assertEquals("1", r.header("X-Extra"));
			JsonObject body = JsonParser.parseString(r.bodyText()).getAsJsonObject();
			assertEquals("local-model", body.get("model").getAsString());
			assertEquals("system", body.getAsJsonArray("messages").get(0).getAsJsonObject().get("role").getAsString());
			assertEquals("function", body.getAsJsonArray("tools").get(0).getAsJsonObject().get("type").getAsString());
			assertEquals("sit", body.getAsJsonArray("tools").get(0).getAsJsonObject().getAsJsonObject("function").get("name").getAsString());
			assertEquals(200, body.get("max_tokens").getAsInt());
			assertEquals("auto", body.get("tool_choice").getAsString());
		}
	}

	@Test
	void sendsToolResultsWithToolCallIdsAndOmitsAuthWithoutKey() throws Exception {
		try (MockHttpServer server = new MockHttpServer()) {
			server.enqueue(Reply.json(200, "{\"choices\":[{\"message\":{\"content\":\"¡Listo! *se sienta*\"},\"finish_reason\":\"stop\"}]}"));
			JsonObject args = new JsonObject();
			List<LlmMessage> history = List.of(
				new LlmMessage.User("sentate"),
				new LlmMessage.Assistant("", List.of(new ToolCall("call_9", "sit", args))),
				new LlmMessage.ToolResults(List.of(new LlmMessage.ToolResult("call_9", "sit", "Te sentaste.", false))));
			LlmResponse resp = provider(server, "", false, 0)
				.complete(new LlmRequest("sys", history, List.of(sitTool()), null, 100)).get(5, TimeUnit.SECONDS);
			assertEquals("¡Listo! *se sienta*", resp.text());

			MockHttpServer.Recorded r = server.requests().getFirst();
			assertNull(r.header("Authorization"));
			JsonObject body = JsonParser.parseString(r.bodyText()).getAsJsonObject();
			assertFalse(body.has("temperature"));
			var messages = body.getAsJsonArray("messages");
			JsonObject assistant = messages.get(2).getAsJsonObject();
			assertEquals("call_9", assistant.getAsJsonArray("tool_calls").get(0).getAsJsonObject().get("id").getAsString());
			assertEquals("{}", assistant.getAsJsonArray("tool_calls").get(0).getAsJsonObject().getAsJsonObject("function").get("arguments").getAsString());
			JsonObject tool = messages.get(3).getAsJsonObject();
			assertEquals("tool", tool.get("role").getAsString());
			assertEquals("call_9", tool.get("tool_call_id").getAsString());
		}
	}

	@Test
	void retriesTransientErrorsButNotClientErrors() throws Exception {
		try (MockHttpServer server = new MockHttpServer()) {
			server.enqueue(Reply.json(503, "{\"error\":\"busy\"}"));
			server.enqueue(Reply.json(200, "{\"choices\":[{\"message\":{\"content\":\"guau\"}}]}"));
			LlmRequest req = new LlmRequest("s", List.of(new LlmMessage.User("hola")), List.of(), null, 50);
			assertEquals("guau", provider(server, "", false, 2).complete(req).get(10, TimeUnit.SECONDS).text());
			assertEquals(2, server.requests().size());
		}
		try (MockHttpServer server = new MockHttpServer()) {
			server.always(r -> Reply.json(400, "{\"error\":\"bad\"}"));
			LlmRequest req = new LlmRequest("s", List.of(new LlmMessage.User("hola")), List.of(), null, 50);
			CompletionException e = assertThrows(CompletionException.class, () -> provider(server, "", false, 3).complete(req).join());
			LlmException le = assertInstanceOf(LlmException.class, e.getCause());
			assertEquals(400, le.status());
			assertEquals(1, server.requests().size(), "un 400 no se reintenta");
		}
	}

	@Test
	void timesOutSlowServers() throws Exception {
		try (MockHttpServer server = new MockHttpServer()) {
			server.always(r -> Reply.json(200, "{\"choices\":[]}").delayed(3000));
			OpenAiCompatibleProvider p = new OpenAiCompatibleProvider(new OpenAiCompatibleProvider.Settings(server.baseUrl(), "", "m",
				"max_tokens", false, Map.of(), Duration.ofMillis(300), 0));
			CompletionException e = assertThrows(CompletionException.class,
				() -> p.complete(new LlmRequest("s", List.of(new LlmMessage.User("x")), List.of(), null, 10)).join());
			LlmException le = assertInstanceOf(LlmException.class, e.getCause());
			assertTrue(le.retryable());
		}
	}

	@Test
	void jsonToolModeEmbedsToolsInPromptAndParsesJsonReply() throws Exception {
		try (MockHttpServer server = new MockHttpServer()) {
			server.enqueue(Reply.json(200, """
				{"choices":[{"message":{"content":"```json\\n{\\"say\\": \\"¡Voy!\\", \\"tool_calls\\": [{\\"name\\": \\"sit\\", \\"arguments\\": {}}]}\\n```"}}]}"""));
			LlmResponse resp = provider(server, "", true, 0)
				.complete(new LlmRequest("Sos Dante", List.of(new LlmMessage.User("sentate")), List.of(sitTool()), 0.5, 100))
				.get(5, TimeUnit.SECONDS);
			assertEquals("¡Voy!", resp.text());
			assertEquals("sit", resp.toolCalls().getFirst().name());
			JsonObject body = JsonParser.parseString(server.requests().getFirst().bodyText()).getAsJsonObject();
			assertFalse(body.has("tools"));
			assertTrue(body.getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString().contains("- sit:"));
		}
	}

	@Test
	void cancellationAbortsTheRequest() throws Exception {
		try (MockHttpServer server = new MockHttpServer()) {
			server.always(r -> Reply.json(200, "{\"choices\":[{\"message\":{\"content\":\"tarde\"}}]}").delayed(2000));
			var future = provider(server, "", false, 0).complete(new LlmRequest("s", List.of(new LlmMessage.User("x")), List.of(), null, 10));
			Thread.sleep(200);
			assertTrue(future.cancel(true));
			assertTrue(future.isCancelled());
		}
	}

	@Test
	void sendsReasoningEffortOnlyWhenConfigured() throws Exception {
		LlmRequest req = new LlmRequest("s", List.of(new LlmMessage.User("x")), List.of(), null, 10);
		try (MockHttpServer server = new MockHttpServer()) {
			server.always(r -> Reply.json(200, "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}"));
			new OpenAiCompatibleProvider(new OpenAiCompatibleProvider.Settings(server.baseUrl(), "", "m", "max_tokens", false,
				Map.of(), Duration.ofSeconds(5), 0)).complete(req).get(5, TimeUnit.SECONDS);
			new OpenAiCompatibleProvider(new OpenAiCompatibleProvider.Settings(server.baseUrl(), "", "m", "max_tokens", false,
				Map.of(), Duration.ofSeconds(5), 0, " none ")).complete(req).get(5, TimeUnit.SECONDS);

			JsonObject first = JsonParser.parseString(server.requests().get(0).bodyText()).getAsJsonObject();
			JsonObject second = JsonParser.parseString(server.requests().get(1).bodyText()).getAsJsonObject();
			assertFalse(first.has("reasoning_effort"));
			assertEquals("none", second.get("reasoning_effort").getAsString());
		}
	}

	@Test
	void stripsReasoningTagsAndToleratesObjectArguments() {
		OpenAiCompatibleProvider p = new OpenAiCompatibleProvider(new OpenAiCompatibleProvider.Settings("http://x", "", "m", "max_tokens",
			false, Map.of(), Duration.ofSeconds(1), 0));
		LlmResponse parsed = invokeParse(p, """
			{"choices":[{"message":{"content":"<think>hmm</think>¡Hola!","tool_calls":[{"id":"a","function":{"name":"wander","arguments":{"x":1}}}]}}]}""");
		assertEquals("¡Hola!", parsed.text());
		assertEquals(1, parsed.toolCalls().getFirst().arguments().get("x").getAsInt());
	}

	private static LlmResponse invokeParse(OpenAiCompatibleProvider p, String json) {
		try {
			var m = OpenAiCompatibleProvider.class.getDeclaredMethod("parseResponse", String.class);
			m.setAccessible(true);
			return (LlmResponse) m.invoke(p, json);
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(e);
		}
	}
}
