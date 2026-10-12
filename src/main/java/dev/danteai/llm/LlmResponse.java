package dev.danteai.llm;

import java.util.List;

/** Respuesta neutral: texto final (puede estar vacío) y herramientas pedidas. */
public record LlmResponse(String text, List<ToolCall> toolCalls, String stopReason, int inputTokens, int outputTokens) {
	public LlmResponse {
		text = text == null ? "" : text;
		toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
		stopReason = stopReason == null ? "" : stopReason;
	}

	public boolean hasToolCalls() {
		return !toolCalls.isEmpty();
	}
}
