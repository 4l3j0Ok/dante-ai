package dev.danteai.llm;

import java.util.List;

/**
 * Mensaje de conversación independiente del proveedor. Cada adaptador lo traduce a su protocolo
 * (OpenAI Chat Completions o Anthropic Messages).
 */
public sealed interface LlmMessage {
	record User(String text) implements LlmMessage {
	}

	/** Turno del modelo: texto opcional y llamadas a herramientas opcionales. */
	record Assistant(String text, List<ToolCall> toolCalls) implements LlmMessage {
		public Assistant {
			text = text == null ? "" : text;
			toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
		}

		public Assistant(String text) {
			this(text, List.of());
		}
	}

	/** Resultados de las herramientas pedidas en el turno anterior del asistente. */
	record ToolResults(List<ToolResult> results) implements LlmMessage {
		public ToolResults {
			results = List.copyOf(results);
		}
	}

	record ToolResult(String callId, String toolName, String content, boolean isError) {
	}
}
