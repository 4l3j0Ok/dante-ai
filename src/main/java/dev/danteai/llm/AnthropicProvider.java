package dev.danteai.llm;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Adaptador nativo para Anthropic Messages API ({@code POST {base}/v1/messages}).
 * <p>
 * Diferencias con OpenAI que se manejan acá: autenticación con {@code x-api-key} y {@code anthropic-version},
 * system prompt como parámetro aparte, contenido en bloques, herramientas con {@code input_schema},
 * llamadas como bloques {@code tool_use} y resultados como bloques {@code tool_result} dentro de un mensaje
 * {@code user}. Los roles deben alternar, así que mensajes consecutivos del mismo rol se fusionan.
 */
public final class AnthropicProvider implements LlmProvider {
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

	public record Settings(String baseUrl, String apiKey, String model, String apiVersion, Duration timeout, int maxRetries) {
	}

	private final Settings settings;

	public AnthropicProvider(Settings settings) {
		this.settings = settings;
	}

	@Override
	public String name() {
		return "anthropic";
	}

	@Override
	public String model() {
		return settings.model;
	}

	@Override
	public CompletableFuture<LlmResponse> complete(LlmRequest request) {
		if (settings.apiKey == null || settings.apiKey.isBlank()) {
			return CompletableFuture.failedFuture(new LlmException("Falta llm.anthropic.api_key", 401, false));
		}
		String body = GSON.toJson(buildBody(request));
		URI uri = URI.create(OpenAiCompatibleProvider.trimSlash(settings.baseUrl) + "/v1/messages");
		Map<String, String> headers = new LinkedHashMap<>();
		headers.put("x-api-key", settings.apiKey.strip());
		headers.put("anthropic-version", settings.apiVersion);
		return HttpSupport.withRetries(() -> HttpSupport.postJson(uri, headers, body, settings.timeout), settings.maxRetries, 1000)
			.thenApply(AnthropicProvider::parseResponse);
	}

	JsonObject buildBody(LlmRequest request) {
		JsonObject body = new JsonObject();
		body.addProperty("model", settings.model);
		body.addProperty("max_tokens", request.maxTokens());
		body.addProperty("system", request.systemPrompt());
		if (request.temperature() != null) {
			body.addProperty("temperature", Math.min(1.0, request.temperature()));
		}

		List<JsonObject> messages = new ArrayList<>();
		for (LlmMessage m : request.messages()) {
			switch (m) {
				case LlmMessage.User u -> append(messages, "user", textBlock(u.text()));
				case LlmMessage.Assistant a -> {
					JsonArray blocks = new JsonArray();
					if (!a.text().isBlank()) blocks.add(textBlock(a.text()));
					for (ToolCall call : a.toolCalls()) {
						JsonObject use = new JsonObject();
						use.addProperty("type", "tool_use");
						use.addProperty("id", call.id());
						use.addProperty("name", call.name());
						use.add("input", call.arguments());
						blocks.add(use);
					}
					if (blocks.isEmpty()) blocks.add(textBlock("…"));
					for (JsonElement b : blocks) append(messages, "assistant", b.getAsJsonObject());
				}
				case LlmMessage.ToolResults r -> {
					for (LlmMessage.ToolResult result : r.results()) {
						JsonObject block = new JsonObject();
						block.addProperty("type", "tool_result");
						block.addProperty("tool_use_id", result.callId());
						block.addProperty("content", result.content());
						if (result.isError()) block.addProperty("is_error", true);
						append(messages, "user", block);
					}
				}
			}
		}
		// La conversación debe empezar con el usuario.
		if (messages.isEmpty() || !messages.getFirst().get("role").getAsString().equals("user")) {
			JsonObject first = new JsonObject();
			first.addProperty("role", "user");
			JsonArray content = new JsonArray();
			content.add(textBlock("(inicio de la conversación)"));
			first.add("content", content);
			messages.addFirst(first);
		}
		JsonArray array = new JsonArray();
		messages.forEach(array::add);
		body.add("messages", array);

		if (!request.tools().isEmpty()) {
			JsonArray tools = new JsonArray();
			for (ToolSpec spec : request.tools()) {
				JsonObject t = new JsonObject();
				t.addProperty("name", spec.name());
				t.addProperty("description", spec.description());
				t.add("input_schema", spec.parameters());
				tools.add(t);
			}
			body.add("tools", tools);
			JsonObject choice = new JsonObject();
			choice.addProperty("type", "auto");
			body.add("tool_choice", choice);
		}
		return body;
	}

	private static void append(List<JsonObject> messages, String role, JsonObject block) {
		if (!messages.isEmpty() && messages.getLast().get("role").getAsString().equals(role)) {
			messages.getLast().getAsJsonArray("content").add(block);
			return;
		}
		JsonObject msg = new JsonObject();
		msg.addProperty("role", role);
		JsonArray content = new JsonArray();
		content.add(block);
		msg.add("content", content);
		messages.add(msg);
	}

	private static JsonObject textBlock(String text) {
		JsonObject o = new JsonObject();
		o.addProperty("type", "text");
		o.addProperty("text", text == null || text.isEmpty() ? "…" : text);
		return o;
	}

	static LlmResponse parseResponse(String raw) {
		JsonObject root;
		try {
			root = JsonParser.parseString(raw).getAsJsonObject();
		} catch (RuntimeException e) {
			throw new LlmException("Respuesta no es JSON válido: " + HttpSupport.abbreviate(raw, 200), 200, false);
		}
		if ("error".equals(stringOf(root.get("type")))) {
			throw new LlmException("Error de Anthropic: " + root.get("error"), 200, false);
		}
		StringBuilder text = new StringBuilder();
		List<ToolCall> calls = new ArrayList<>();
		if (root.has("content") && root.get("content").isJsonArray()) {
			for (JsonElement el : root.getAsJsonArray("content")) {
				if (!el.isJsonObject()) continue;
				JsonObject block = el.getAsJsonObject();
				String type = stringOf(block.get("type"));
				if ("text".equals(type)) {
					if (!text.isEmpty()) text.append('\n');
					text.append(stringOf(block.get("text")));
				} else if ("tool_use".equals(type)) {
					JsonObject input = block.has("input") && block.get("input").isJsonObject() ? block.getAsJsonObject("input") : new JsonObject();
					calls.add(new ToolCall(stringOf(block.get("id")), stringOf(block.get("name")), input));
				}
			}
		}
		int in = 0;
		int out = 0;
		if (root.has("usage") && root.get("usage").isJsonObject()) {
			JsonObject usage = root.getAsJsonObject("usage");
			in = usage.has("input_tokens") ? usage.get("input_tokens").getAsInt() : 0;
			out = usage.has("output_tokens") ? usage.get("output_tokens").getAsInt() : 0;
		}
		return new LlmResponse(text.toString().strip(), calls, stringOf(root.get("stop_reason")), in, out);
	}

	private static String stringOf(JsonElement e) {
		return e == null || e.isJsonNull() || !e.isJsonPrimitive() ? "" : e.getAsString();
	}
}
