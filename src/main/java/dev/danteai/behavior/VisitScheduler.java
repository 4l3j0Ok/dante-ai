package dev.danteai.behavior;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Máquina de estados de las visitas: tras {@code afterSeconds} con compañía, si hay otros jugadores conectados que
 * no están cerca, Dante avisa que va a visitar a uno. Si nadie dice nada en {@code replySeconds}, o alguien lo
 * confirma, se va; si le piden que se quede, se queda {@code staySeconds} más. No toca el mundo: devuelve acciones.
 */
public final class VisitScheduler {
	public enum Type {
		NONE, ANNOUNCE, LEAVE, STAY, CANCEL
	}

	public enum Reply {
		STAY, GO
	}

	public record Action(Type type, UUID target) {
		static final Action NONE = new Action(Type.NONE, null);
	}

	/** Sin nadie cerca durante más de esto se reinicia el reloj; antes bastaba con alejarse un minuto. */
	private static final long COMPANY_GRACE_SECONDS = 600;

	private final long afterSeconds;
	private final long replySeconds;
	private final long staySeconds;
	private final Map<UUID, Long> lastVisit = new HashMap<>();
	private long dueAt = -1;
	private long lastCompany = Long.MIN_VALUE;
	private UUID pending;
	private long deadline;

	public VisitScheduler(long afterSeconds, long replySeconds, long staySeconds) {
		this.afterSeconds = afterSeconds;
		this.replySeconds = replySeconds;
		this.staySeconds = staySeconds;
	}

	public boolean sameSettings(long afterSeconds, long replySeconds, long staySeconds) {
		return this.afterSeconds == afterSeconds && this.replySeconds == replySeconds && this.staySeconds == staySeconds;
	}

	public boolean pending() {
		return pending != null;
	}

	public UUID pendingTarget() {
		return pending;
	}

	public void reset() {
		pending = null;
		dueAt = -1;
	}

	/**
	 * @param companyNearby hay al menos un jugador cerca de Dante
	 * @param candidates    jugadores conectados que NO están cerca (posibles destinos)
	 */
	public Action tick(long now, boolean companyNearby, List<UUID> candidates) {
		if (pending != null) {
			UUID target = pending;
			if (!candidates.contains(target)) {
				pending = null;
				dueAt = -1;
				return new Action(Type.CANCEL, target);
			}
			if (now >= deadline) {
				pending = null;
				left(now, target);
				return new Action(Type.LEAVE, target);
			}
			return Action.NONE;
		}
		if (companyNearby) {
			lastCompany = now;
			if (dueAt < 0) dueAt = now + afterSeconds;
		} else if (dueAt >= 0 && now - lastCompany > COMPANY_GRACE_SECONDS) {
			dueAt = -1;
		}
		if (dueAt >= 0 && now >= dueAt && !candidates.isEmpty()) {
			UUID target = candidates.get(0);
			for (UUID c : candidates) {
				if (lastVisit.getOrDefault(c, Long.MIN_VALUE) < lastVisit.getOrDefault(target, Long.MIN_VALUE)) target = c;
			}
			pending = target;
			deadline = now + replySeconds;
			return new Action(Type.ANNOUNCE, target);
		}
		return Action.NONE;
	}

	/** Respuesta de un jugador al aviso. Sin aviso pendiente no hace nada. */
	public Action reply(long now, Reply reply) {
		if (pending == null) return Action.NONE;
		UUID target = pending;
		pending = null;
		if (reply == Reply.GO) {
			left(now, target);
			return new Action(Type.LEAVE, target);
		}
		dueAt = now + staySeconds;
		return new Action(Type.STAY, target);
	}

	private void left(long now, UUID target) {
		lastVisit.put(target, now);
		dueAt = -1;
	}
}
