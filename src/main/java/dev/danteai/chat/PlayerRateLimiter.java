package dev.danteai.chat;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/** Antispam por jugador: espera mínima entre mensajes y cupo por hora (ventana deslizante). */
public final class PlayerRateLimiter {
	public enum Decision { ALLOWED, COOLDOWN, HOURLY_LIMIT }

	private final long cooldownMillis;
	private final int perHour;
	private final LongSupplier clock;
	private final Map<UUID, Deque<Long>> history = new HashMap<>();

	public PlayerRateLimiter(long cooldownMillis, int perHour, LongSupplier clock) {
		this.cooldownMillis = cooldownMillis;
		this.perHour = perHour;
		this.clock = clock;
	}

	public synchronized Decision tryAcquire(UUID player) {
		long now = clock.getAsLong();
		Deque<Long> times = history.computeIfAbsent(player, k -> new ArrayDeque<>());
		while (!times.isEmpty() && now - times.peekFirst() >= 3_600_000L) {
			times.pollFirst();
		}
		if (!times.isEmpty() && now - times.peekLast() < cooldownMillis) {
			return Decision.COOLDOWN;
		}
		if (times.size() >= perHour) {
			return Decision.HOURLY_LIMIT;
		}
		times.addLast(now);
		return Decision.ALLOWED;
	}

	public synchronized void forget(UUID player) {
		history.remove(player);
	}
}
