package dev.danteai.llm;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/**
 * Protocolo de herramientas en texto para modelos sin tool calling nativo. El modelo debe responder:
 * <pre>{"say": "texto para el chat", "tool_calls": [{"name": "sit", "arguments": {}}]}</pre>
 * Si el texto no es JSON, se toma completo como mensaje sin herramientas.
 */
public final class JsonToolProtocol {
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

	public record Parsed(String say, List<ToolCall> toolCalls) {
	}

	private JsonToolProtocol() {
	}

	public static String instructions(List<ToolSpec> tools) {
		StringBuilder sb = new StringBuilder();
		sb.append("FORMATO DE RESPUESTA OBLIGATORIO: respondé SOLO con un objeto JSON, sin texto adicional:\n");
		sb.append("{\"say\": \"lo que decís en el chat (puede ser vacío)\", \"tool_calls\": [{\"name\": \"herramienta\", \"arguments\": {...}}]}\n");
		sb.append("Usá \"tool_calls\": [] si no necesitás acciones. Después de ejecutar acciones vas a recibir sus resultados.\n");
		sb.append("Herramientas disponibles:\n");
		for (ToolSpec t : tools) {
			sb.append("- ").append(t.name()).append(": ").append(t.description())
				.append(" Parámetros: ").append(GSON.toJson(t.parameters().get("properties"))).append('\n');
		}
		return sb.toString();
	}

	public static String render(LlmMessage.Assistant a) {
		JsonObject o = new JsonObject();
		o.addProperty("say", a.text());
		JsonArray calls = new JsonArray();
		for (ToolCall c : a.toolCalls()) {
			JsonObject call = new JsonObject();
			call.addProperty("name", c.name());
			call.add("arguments", c.arguments());
			calls.add(call);
		}
		o.add("tool_calls", calls);
		return GSON.toJson(o);
	}

	public static String renderResults(LlmMessage.ToolResults results) {
		StringBuilder sb = new StringBuilder("[Resultados de tus acciones]\n");
		for (LlmMessage.ToolResult r : results.results()) {
			sb.append("- ").append(r.toolName()).append(r.isError() ? " (FALLÓ): " : ": ").append(r.content()).append('\n');
		}
		sb.append("Respondé con el mismo formato JSON.");
		return sb.toString();
	}

	public static Parsed parse(String content) {
		String text = OpenAiCompatibleProvider.stripThinking(content == null ? "" : content);
		String json = extractJsonObject(text);
		if (json == null) {
			return new Parsed(text, List.of());
		}
		try {
			JsonElement element = JsonParser.parseString(json);
			if (!element.isJsonObject()) return new Parsed(text, List.of());
			JsonObject o = element.getAsJsonObject();
			String say = o.has("say") && o.get("say").isJsonPrimitive() ? o.get("say").getAsString() : "";
			List<ToolCall> calls = new ArrayList<>();
			if (o.has("tool_calls") && o.get("tool_calls").isJsonArray()) {
				int i = 0;
				for (JsonElement el : o.getAsJsonArray("tool_calls")) {
					if (!el.isJsonObject()) continue;
					JsonObject call = el.getAsJsonObject();
					if (!call.has("name") || !call.get("name").isJsonPrimitive()) continue;
					JsonObject args = call.has("arguments") ? OpenAiCompatibleProvider.parseArguments(call.get("arguments")) : new JsonObject();
					calls.add(new ToolCall("json_" + (i++), call.get("name").getAsString(), args));
				}
			}
			return new Parsed(say, calls);
		} catch (RuntimeException e) {
			return new Parsed(text, List.of());
		}
	}

	/** Extrae el primer objeto JSON balanceado (tolera bloques ```json ... ```). */
	static String extractJsonObject(String text) {
		int start = text.indexOf('{');
		if (start < 0) return null;
		int depth = 0;
		boolean inString = false;
		boolean escape = false;
		for (int i = start; i < text.length(); i++) {
			char c = text.charAt(i);
			if (inString) {
				if (escape) escape = false;
				else if (c == '\\') escape = true;
				else if (c == '"') inString = false;
				continue;
			}
			if (c == '"') inString = true;
			else if (c == '{') depth++;
			else if (c == '}') {
				depth--;
				if (depth == 0) return text.substring(start, i + 1);
			}
		}
		return null;
	}
}
