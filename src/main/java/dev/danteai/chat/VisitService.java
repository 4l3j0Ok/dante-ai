package dev.danteai.chat;

import dev.danteai.behavior.Tasks;
import dev.danteai.behavior.VisitScheduler;
import dev.danteai.config.DanteConfig;
import dev.danteai.entity.DanteEntity;
import dev.danteai.util.DanteLog;
import dev.danteai.world.DanteManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Visitas: después de un rato largo con alguien, y solo si hay más jugadores conectados, Dante avisa que va a
 * visitar a otro. Se queda si se lo piden, se va si se lo confirman o si nadie dice nada a tiempo.
 */
public final class VisitService {
	private final DanteManager manager;
	private final Random random = new Random();
	private VisitScheduler scheduler;

	public VisitService(DanteManager manager) {
		this.manager = manager;
	}

	public boolean pending() {
		return scheduler != null && scheduler.pending();
	}

	/** Una vez por segundo, en el hilo del servidor. */
	public void tickSecond() {
		DanteConfig.Behavior b = manager.config().behavior;
		if (!b.visit_enabled) {
			if (scheduler != null) scheduler.reset();
			return;
		}
		VisitScheduler s = scheduler(b);
		DanteEntity body = manager.body();
		List<ServerPlayer> nearby = List.of();
		List<UUID> candidates = new ArrayList<>();
		if (body != null) {
			ServerLevel level = (ServerLevel) body.level();
			nearby = manager.brain().nearbyPlayers(body, level, b.social_radius);
			for (ServerPlayer p : manager.server().getPlayerList().getPlayers()) {
				if (!p.isSpectator() && !nearby.contains(p)) candidates.add(p.getUUID());
			}
		}
		VisitScheduler.Action action = s.tick(nowSeconds(), !nearby.isEmpty(), candidates);
		execute(action, nearby, false);
	}

	/** Respuesta de un jugador mientras hay un aviso pendiente. */
	public void reply(VisitScheduler.Reply reply) {
		if (scheduler == null) return;
		DanteEntity body = manager.body();
		List<ServerPlayer> nearby = body == null ? List.of()
			: manager.brain().nearbyPlayers(body, (ServerLevel) body.level(), manager.config().behavior.social_radius);
		execute(scheduler.reply(nowSeconds(), reply), nearby, true);
	}

	private VisitScheduler scheduler(DanteConfig.Behavior b) {
		long after = b.visit_after_minutes * 60L;
		long reply = b.visit_reply_seconds;
		long stay = b.visit_stay_minutes * 60L;
		if (scheduler == null || !scheduler.sameSettings(after, reply, stay)) scheduler = new VisitScheduler(after, reply, stay);
		return scheduler;
	}

	private long nowSeconds() {
		return manager.server().overworld().getGameTime() / 20L;
	}

	private void execute(VisitScheduler.Action action, List<ServerPlayer> nearby, boolean confirmed) {
		if (action.type() == VisitScheduler.Type.NONE || action.type() == VisitScheduler.Type.CANCEL) return;
		ServerPlayer target = manager.server().getPlayerList().getPlayer(action.target());
		if (target == null) return;
		DanteLog.info("Visita: {} → {} (confirmada: {})", action.type(), target.getGameProfile().getName(), confirmed);
		ServerPlayer focus = nearby.isEmpty() ? null : nearby.get(0);
		DanteConfig.Behavior b = manager.config().behavior;
		String name = target.getGameProfile().getName();
		switch (action.type()) {
			case ANNOUNCE -> manager.conversation().react(focus,
				"Llevás un buen rato con este grupo y vas a ir a visitar a " + name + ". Avisales que si quieren que te quedes tienen "
					+ b.visit_reply_seconds + " segundos para decirte «quedate» o «no te vayas»; si no, te vas.",
				pick(
					"Mi rey, ya llevamos un buen rato juntos. Voy a visitar a " + name + ". Si querés que me quede, decime «quedate»; tenés " + b.visit_reply_seconds + " segundos.",
					"Che, mi rey, me estoy yendo a lo de " + name + ". Si no te copa, decime «no te vayas» y me quedo.",
					"Me llama " + name + ", mi rey. Me voy en " + b.visit_reply_seconds + " segundos, salvo que me pidas que me quede."));
			case STAY -> manager.conversation().react(focus,
				"Te pidieron que no te vayas: te quedás otros " + b.visit_stay_minutes + " minutos con este grupo en lugar de ir con " + name + ".",
				pick(
					"Uh, mi rey, ¿tanto me querés? Bueno, me quedo otros " + b.visit_stay_minutes + " minutos, pero no te acostumbres.",
					"Está bien, mi rey, me quedo " + b.visit_stay_minutes + " minutos más. " + name + " puede esperar."));
			case LEAVE -> {
				manager.conversation().react(focus,
					confirmed ? "Te confirmaron que te vayas: te vas ahora a visitar a " + name + ". Despedite del grupo."
						: "Se acabó el tiempo y nadie dijo nada: te vas ahora a visitar a " + name + ". Despedite del grupo.",
					confirmed
						? pick("Dale, mi rey, ya me voy con " + name + ". ¡Chau!", "Entendido, mi rey. Voy a lo de " + name + ". Portate bien.")
						: pick("Se acabó el tiempo, mi rey. Me voy con " + name + ".", "Nadie dijo nada, así que me voy con " + name + ". ¡Chau, mi rey!"));
				goTo(target, b);
			}
			default -> {
			}
		}
	}

	private void goTo(ServerPlayer target, DanteConfig.Behavior b) {
		DanteEntity body = manager.body();
		boolean sameDimension = body != null && body.level() == target.level();
		boolean moved = false;
		if (b.teleport_enabled && (sameDimension || b.teleport_across_dimensions || body == null)) moved = manager.teleportNear(target);
		if (!moved && sameDimension && body.distanceTo(target) <= 64) {
			manager.brain().startTask(new Tasks.Approach(target));
			moved = true;
		}
		if (!moved) DanteLog.info("Dante no pudo ir a visitar a {}", target.getGameProfile().getName());
	}

	private String pick(String... options) {
		return options[random.nextInt(options.length)];
	}
}
