package dev.danteai.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Logger del mod. Los mensajes de depuración se emiten a nivel INFO solo si {@code logging.debug} está activo,
 * para no depender de la configuración de log4j del servidor.
 */
public final class DanteLog {
	public static final Logger LOGGER = LoggerFactory.getLogger("DanteAI");
	private static volatile boolean debug;
	private static volatile boolean prompts;
	private static volatile boolean toolCalls = true;

	private DanteLog() {
	}

	public static void configure(boolean debugEnabled, boolean logPrompts, boolean logToolCalls) {
		debug = debugEnabled;
		prompts = logPrompts;
		toolCalls = logToolCalls;
	}

	public static void info(String msg, Object... args) {
		LOGGER.info(msg, args);
	}

	public static void warn(String msg, Object... args) {
		LOGGER.warn(msg, args);
	}

	public static void error(String msg, Throwable t) {
		LOGGER.error(msg, t);
	}

	public static void debug(String msg, Object... args) {
		if (debug) LOGGER.info("[debug] " + msg, args);
	}

	public static void prompt(String msg, Object... args) {
		if (prompts) LOGGER.info("[llm] " + msg, args);
	}

	public static void tool(String msg, Object... args) {
		if (toolCalls) LOGGER.info("[tool] " + msg, args);
	}
}
