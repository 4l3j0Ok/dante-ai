package dev.danteai.tools;

import com.google.gson.JsonObject;
import dev.danteai.llm.ToolSpec;

/**
 * Acción que el modelo puede pedir. Se ejecuta siempre en el hilo del servidor, con argumentos ya parseados como
 * JSON; la implementación valida todo (jugadores, distancias, permisos, objetos) y devuelve el resultado real.
 */
public interface DanteTool {
	String name();

	String description();

	JsonObject parameters();

	/** Intervalo mínimo global entre usos, en ticks (0 = sin límite). */
	default int cooldownTicks() {
		return 0;
	}

	/** Usos máximos dentro de un mismo turno de conversación. */
	default int maxPerTurn() {
		return 3;
	}

	String execute(ToolContext ctx, JsonObject args) throws ToolException;

	default ToolSpec spec() {
		return new ToolSpec(name(), description(), parameters());
	}
}
