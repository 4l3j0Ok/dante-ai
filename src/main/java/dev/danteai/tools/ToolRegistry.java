package dev.danteai.tools;

import com.google.gson.JsonObject;
import dev.danteai.llm.LlmMessage;
import dev.danteai.llm.ToolCall;
import dev.danteai.llm.ToolSpec;
import dev.danteai.util.DanteLog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Registro y ejecución segura de herramientas. El modelo solo puede elegir nombres registrados con argumentos
 * JSON; acá se rechazan herramientas inexistentes, argumentos inválidos, abusos de frecuencia y excepciones, y
 * siempre se devuelve un resultado textual real al modelo.
 */
public final class ToolRegistry {
	private final Map<String, DanteTool> tools = new LinkedHashMap<>();
	private final Map<String, Long> lastUse = new HashMap<>();
	private final LongSupplier clock;

	public ToolRegistry(LongSupplier gameTimeTicks) {
		this.clock = gameTimeTicks;
	}

	public ToolRegistry register(DanteTool tool) {
		tools.put(tool.name(), tool);
		return this;
	}

	public List<ToolSpec> specs() {
		List<ToolSpec> out = new ArrayList<>();
		for (DanteTool t : tools.values()) out.add(t.spec());
		return out;
	}

	public boolean has(String name) {
		return tools.containsKey(name);
	}

	public LlmMessage.ToolResult execute(ToolContext ctx, ToolCall call) {
		DanteTool tool = tools.get(call.name());
		if (tool == null) {
			return fail(call, "la herramienta '" + call.name() + "' no existe. Disponibles: " + String.join(", ", tools.keySet()));
		}
		JsonObject args = call.arguments();
		if (args.has("__invalid_json__")) {
			return fail(call, "los argumentos no eran JSON válido");
		}
		if (ctx.countUse(tool.name()) > tool.maxPerTurn()) {
			return fail(call, "ya usaste " + tool.name() + " demasiadas veces en esta respuesta");
		}
		long now = clock.getAsLong();
		Long last = lastUse.get(tool.name());
		if (tool.cooldownTicks() > 0 && last != null && now - last < tool.cooldownTicks()) {
			long wait = (tool.cooldownTicks() - (now - last)) / 20 + 1;
			return fail(call, "tenés que esperar " + wait + " segundos para volver a usar " + tool.name());
		}
		try {
			String result = tool.execute(ctx, args);
			lastUse.put(tool.name(), now);
			DanteLog.tool("{}({}) -> {}", tool.name(), args, result);
			return new LlmMessage.ToolResult(call.id(), call.name(), result, false);
		} catch (ToolException e) {
			DanteLog.tool("{}({}) FALLÓ: {}", tool.name(), args, e.getMessage());
			return fail(call, e.getMessage());
		} catch (RuntimeException e) {
			DanteLog.error("Error inesperado ejecutando " + tool.name(), e);
			return fail(call, "error interno al ejecutar la acción");
		}
	}

	private static LlmMessage.ToolResult fail(ToolCall call, String message) {
		return new LlmMessage.ToolResult(call.id(), call.name(), message, true);
	}
}
