package dev.danteai.llm;

import java.util.concurrent.CompletableFuture;

/**
 * Proveedor de modelo de lenguaje. Las implementaciones no bloquean: devuelven un future que se completa
 * en un hilo del cliente HTTP. Cancelar el future aborta la solicitud HTTP en curso.
 */
public interface LlmProvider {
	String name();

	String model();

	CompletableFuture<LlmResponse> complete(LlmRequest request);
}
