package dev.danteai.llm;

import com.google.gson.JsonObject;

/** Llamada a herramienta pedida por el modelo. Los argumentos siempre llegan como objeto JSON. */
public record ToolCall(String id, String name, JsonObject arguments) {
	public ToolCall {
		arguments = arguments == null ? new JsonObject() : arguments;
	}
}
