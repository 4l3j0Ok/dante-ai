package dev.danteai.llm;

import java.util.List;

/**
 * Solicitud neutral al proveedor.
 *
 * @param temperature {@code null} para no enviarla
 */
public record LlmRequest(String systemPrompt, List<LlmMessage> messages, List<ToolSpec> tools, Double temperature, int maxTokens) {
	public LlmRequest {
		messages = List.copyOf(messages);
		tools = tools == null ? List.of() : List.copyOf(tools);
	}
}
