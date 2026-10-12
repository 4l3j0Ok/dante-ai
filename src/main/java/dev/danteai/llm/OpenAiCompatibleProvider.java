package dev.danteai.llm;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Adaptador para APIs estilo OpenAI Chat Completions ({@code POST {base}/chat/completions}).
 * Compatible con LM Studio, Ollama, vLLM, llama.cpp server, OpenRouter y la API de OpenAI.
 * <p>
 * En modo {@code json} no se envía el parámetro {@code tools}: las herramientas se describen en el prompt y el
 * modelo responde con un objeto JSON. Sirve para modelos locales sin plantilla de tool calling.
 */
public final class OpenAiCompatibleProvider implements LlmProvider {
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

	public record Settings(String baseUrl, String apiKey, String model, String maxTokensField, boolean jsonToolsMode,
			Map<String, String> extraHeaders, Duration timeout, int maxRetries, String reasoningEffort) {
		public Settings(String baseUrl, String apiKey, String model, String maxTokensField, boolean jsonToolsMode,
				Map<String, String> extraHeaders, Duration timeout, int maxRetries) {
			this(baseUrl, apiKey, model, maxTokensField, jsonToolsMode, extraHeaders, timeout, maxRetries, "");
		}
	}

	private final Settings settings;

	public OpenAiCompatibleProvider(Settings settings) {
		this.settings = settings;
	}

	@Override
	public String name() {
		return settings.jsonToolsMode ? "openai-compatible (json tools)" : "openai-compatible";
	}

	@Override
	public String model() {
		return settings.model;
	}

	@Override
	public CompletableFuture<LlmResponse> complete(LlmRequest request) {
		String body = GSON.toJson(buildBody(request));
		URI uri = URI.create(trimSlash(settings.baseUrl) + "/chat/completions");
		Map<String, String> headers = new LinkedHashMap<>(settings.extraHeaders == null ? Map.of() : settings.extraHeaders);
		if (settings.apiKey != null && !settings.apiKey.isBlank()) {
			headers.put("Authorization", "Bearer " + settings.apiKey.strip());
		}
		return HttpSupport.withRetries(() -> HttpSupport.postJson(uri, headers, body, settings.timeout), settings.maxRetries, 800)
			.thenApply(this::parseResponse);
	}

	JsonObject buildBody(LlmRequest request) {
		JsonObject body = new JsonObject();
		body.addProperty("model", settings.model);
		JsonArray messages = new JsonArray();
		String system = request.systemPrompt();
		if (settings.jsonToolsMode && !request.tools().isEmpty()) {
			system = system + "\n\n" + JsonToolProtocol.instructions(request.tools());
		}
		messages.add(message("system", system));
		for (LlmMessage m : request.messages()) {
			switch (m) {
				case LlmMessage.User u -> messages.add(message("user", u.text()));
				case LlmMessage.Assistant a -> messages.add(settings.jsonToolsMode ? message("assistant", JsonToolProtocol.render(a)) : assistant(a));
				case LlmMessage.ToolResults r -> {
					if (settings.jsonToolsMode) {
						messages.add(message("user", JsonToolProtocol.renderResults(r)));
					} else {
						for (LlmMessage.ToolResult result : r.results()) {
							JsonObject tool = new JsonObject();
							tool.addProperty("role", "tool");
							tool.addProperty("tool_call_id", result.callId());
							tool.addProperty("content", result.isError() ? "ERROR: " + result.content() : result.content());
							messages.add(tool);
						}
					}
				}
			}
		}
		body.add("messages", messages);
		if (!settings.jsonToolsMode && !request.tools().isEmpty()) {
			JsonArray tools = new JsonArray();
			for (ToolSpec spec : request.tools()) {
				JsonObject fn = new JsonObject();
				fn.addProperty("name", spec.name());
				fn.addProperty("description", spec.description());
				fn.add("parameters", spec.parameters());
				JsonObject tool = new JsonObject();
				tool.addProperty("type", "function");
				tool.add("function", fn);
				tools.add(tool);
			}
			body.add("tools", tools);
			body.addProperty("tool_choice", "auto");
		}
		if (request.temperature() != null) {
			body.addProperty("temperature", request.temperature());
		}
		if (settings.reasoningEffort != null && !settings.reasoningEffort.isBlank()) {
			body.addProperty("reasoning_effort", settings.reasoningEffort.strip());
		}
		body.addProperty(settings.maxTokensField, request.maxTokens());
		body.addProperty("stream", false);
		return body;
	}

	private static JsonObject message(String role, String content) {
		JsonObject o = new JsonObject();
		o.addProperty("role", role);
		o.addProperty("content", content);
		return o;
	}

	private static JsonObject assistant(LlmMessage.Assistant a) {
		JsonObject o = new JsonObject();
		o.addProperty("role", "assistant");
		if (a.toolCalls().isEmpty()) {
			o.addProperty("content", a.text());
			return o;
		}
		if (a.text().isEmpty()) {
			o.add("content", com.google.gson.JsonNull.INSTANCE);
		} else {
			o.addProperty("content", a.text());
		}
		JsonArray calls = new JsonArray();
		for (ToolCall call : a.toolCalls()) {
			JsonObject fn = new JsonObject();
			fn.addProperty("name", call.name());
			fn.addProperty("arguments", GSON.toJson(call.arguments()));
			JsonObject c = new JsonObject();
			c.addProperty("id", call.id());
			c.addProperty("type", "function");
			c.add("function", fn);
			calls.add(c);
		}
		o.add("tool_calls", calls);
		return o;
	}

	LlmResponse parseResponse(String raw) {
		JsonObject root;
		try {
			root = JsonParser.parseString(raw).getAsJsonObject();
		} catch (RuntimeException e) {
			throw new LlmException("Respuesta no es JSON válido: " + HttpSupport.abbreviate(raw, 200), 200, false);
		}
		if (root.has("error") && !root.get("error").isJsonNull()) {
			throw new LlmException("Error del proveedor: " + root.get("error"), 200, false);
		}
		JsonArray choices = root.has("choices") && root.get("choices").isJsonArray() ? root.getAsJsonArray("choices") : null;
		if (choices == null || choices.isEmpty()) {
			throw new LlmException("Respuesta sin 'choices'", 200, false);
		}
		JsonObject choice = choices.get(0).getAsJsonObject();
		JsonObject message = choice.has("message") && choice.get("message").isJsonObject() ? choice.getAsJsonObject("message") : new JsonObject();
		String content = stringOrEmpty(message.get("content"));
		String finish = stringOrEmpty(choice.get("finish_reason"));
		int in = 0;
		int out = 0;
		if (root.has("usage") && root.get("usage").isJsonObject()) {
			JsonObject usage = root.getAsJsonObject("usage");
			in = usage.has("prompt_tokens") ? usage.get("prompt_tokens").getAsInt() : 0;
			out = usage.has("completion_tokens") ? usage.get("completion_tokens").getAsInt() : 0;
		}
		if (settings.jsonToolsMode) {
			JsonToolProtocol.Parsed parsed = JsonToolProtocol.parse(content);
			return new LlmResponse(parsed.say(), parsed.toolCalls(), finish, in, out);
		}
		List<ToolCall> calls = new ArrayList<>();
		if (message.has("tool_calls") && message.get("tool_calls").isJsonArray()) {
			for (JsonElement el : message.getAsJsonArray("tool_calls")) {
				JsonObject call = el.getAsJsonObject();
				JsonObject fn = call.has("function") ? call.getAsJsonObject("function") : null;
				if (fn == null || !fn.has("name")) continue;
				String id = call.has("id") && !call.get("id").isJsonNull() ? call.get("id").getAsString() : "call_" + UUID.randomUUID();
				calls.add(new ToolCall(id, fn.get("name").getAsString(), parseArguments(fn.get("arguments"))));
			}
		}
		return new LlmResponse(stripThinking(content), calls, finish, in, out);
	}

	/** Los argumentos llegan como string JSON (OpenAI) o como objeto (algunos servidores locales). */
	static JsonObject parseArguments(JsonElement element) {
		if (element == null || element.isJsonNull()) return new JsonObject();
		if (element.isJsonObject()) return element.getAsJsonObject();
		String s = element.getAsString().strip();
		if (s.isEmpty()) return new JsonObject();
		try {
			JsonElement parsed = JsonParser.parseString(s);
			return parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
		} catch (JsonParseException e) {
			JsonObject invalid = new JsonObject();
			invalid.addProperty("__invalid_json__", s);
			return invalid;
		}
	}

	/** Algunos modelos de razonamiento locales devuelven el razonamiento entre etiquetas &lt;think&gt;. */
	static String stripThinking(String content) {
		return content.replaceAll("(?s)<think>.*?</think>", "").strip();
	}

	private static String stringOrEmpty(JsonElement e) {
		if (e == null || e.isJsonNull()) return "";
		if (e.isJsonPrimitive()) return e.getAsString();
		if (e.isJsonArray()) {
			StringBuilder sb = new StringBuilder();
			for (JsonElement part : e.getAsJsonArray()) {
				if (part.isJsonObject() && part.getAsJsonObject().has("text")) sb.append(part.getAsJsonObject().get("text").getAsString());
			}
			return sb.toString();
		}
		return "";
	}

	static String trimSlash(String url) {
		String u = url.strip();
		while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
		return u;
	}
}
