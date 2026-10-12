package dev.danteai.chat;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Detecta que Dante repita el mismo mensaje a la misma persona en una ventana corta. */
public final class RecentMessages {
	private record Recent(String text, long at) {
	}

	private final Map<UUID, Recent> recent = new ConcurrentHashMap<>();
	private final long windowMs;

	public RecentMessages(long windowMs) {
		this.windowMs = windowMs;
	}

	/** Registra el mensaje y devuelve {@code true} si es un duplicado reciente para esa clave. */
	public boolean isDuplicate(UUID key, String text, long nowMs) {
		Recent last = recent.get(key);
		if (last != null && nowMs - last.at() < windowMs && TextUtil.sameMessage(last.text(), text)) return true;
		recent.put(key, new Recent(text, nowMs));
		return false;
	}
}
