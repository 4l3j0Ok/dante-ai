package dev.danteai.behavior;

import java.util.EnumMap;
import java.util.Map;
import java.util.Random;

/**
 * Decide la próxima actividad autónoma combinando pesos configurables, necesidades internas, entorno y azar.
 * No depende de Minecraft para poder probarse con contextos sintéticos.
 */
public final class BehaviorSelector {
	/** Parámetros configurables (subconjunto de {@code behavior} en la configuración). */
	public record Weights(boolean wander, boolean follow, boolean explore, boolean socialize, boolean rest, boolean teleport,
			double followProbability, double idleProbability, double exploreProbability, double socializeProbability,
			double ignoreProbability, int teleportDistance, int lonelyTicksBeforeSeeking) {
	}

	/** Foto del entorno relevante para decidir. */
	public record Situation(boolean night, boolean raining, int nearbyPlayers, int playersInDimension, int playersOnline,
			double nearestPlayerDistance, boolean interestingThingNearby, boolean teleportOffCooldown, long ticksAlone,
			boolean inDanger) {
	}

	private BehaviorSelector() {
	}

	public static Activity choose(Situation s, Needs needs, Weights w, Random random) {
		if (s.inDanger()) {
			return Activity.FLEEING;
		}
		if (shouldSeekPlayers(s, w, random)) {
			return Activity.TELEPORTING;
		}
		Map<Activity, Double> weights = weights(s, needs, w);
		double total = 0;
		for (double v : weights.values()) total += v;
		if (total <= 0) return Activity.IDLE;
		double roll = random.nextDouble() * total;
		for (Map.Entry<Activity, Double> e : weights.entrySet()) {
			roll -= e.getValue();
			if (roll <= 0) return e.getKey();
		}
		return Activity.IDLE;
	}

	/** Teletransporte espontáneo: solo si está lejos de todos (o solo hace rato) y el cooldown lo permite. */
	static boolean shouldSeekPlayers(Situation s, Weights w, Random random) {
		if (!w.teleport() || !s.teleportOffCooldown() || s.playersOnline() == 0) return false;
		boolean farFromEveryone = s.playersInDimension() == 0 || s.nearestPlayerDistance() > w.teleportDistance();
		if (!farFromEveryone) return false;
		if (s.ticksAlone() < w.lonelyTicksBeforeSeeking()) return false;
		// Aun así no siempre: a veces prefiere seguir con lo suyo.
		return random.nextDouble() < 0.6;
	}

	public static Map<Activity, Double> weights(Situation s, Needs n, Weights w) {
		Map<Activity, Double> map = new EnumMap<>(Activity.class);
		boolean players = s.nearbyPlayers() > 0;
		if (w.wander()) {
			map.put(Activity.WANDERING, 0.30 * (0.6 + n.energy()) * (s.raining() ? 0.6 : 1.0));
		}
		if (w.follow() && players) {
			map.put(Activity.FOLLOWING, w.followProbability() * (0.6 + n.social()) * (1.0 - w.ignoreProbability()));
		}
		if (w.rest()) {
			double tired = 1.0 + 2.5 * (1.0 - n.energy());
			map.put(Activity.RESTING, w.idleProbability() * tired * (s.night() ? 2.0 : 1.0) * (s.raining() ? 1.3 : 1.0));
		}
		if (w.socialize() && players) {
			map.put(Activity.SOCIALIZING, w.socializeProbability() * (0.4 + n.social()) * (1.0 - w.ignoreProbability()));
		}
		if (w.explore()) {
			map.put(Activity.EXPLORING, w.exploreProbability() * (0.4 + n.curiosity()) * (s.interestingThingNearby() ? 1.6 : 0.8)
				* (s.night() ? 0.6 : 1.0));
		}
		map.put(Activity.IDLE, 0.08);
		return map;
	}

	/** Duración en ticks de una actividad autónoma. */
	public static int durationTicks(Activity activity, int followMinSeconds, int followMaxSeconds, int restMinSeconds,
			int restMaxSeconds, Random random) {
		return switch (activity) {
			case FOLLOWING -> 20 * between(followMinSeconds, followMaxSeconds, random);
			case RESTING -> 20 * between(restMinSeconds, restMaxSeconds, random);
			case WANDERING -> 20 * between(15, 45, random);
			case SOCIALIZING -> 20 * between(10, 30, random);
			case EXPLORING -> 20 * between(12, 35, random);
			case IDLE -> 20 * between(3, 10, random);
			case FLEEING -> 20 * between(3, 6, random);
			case TELEPORTING -> 20;
			case INTERACTING -> 20 * 60;
		};
	}

	private static int between(int min, int max, Random random) {
		return max <= min ? min : min + random.nextInt(max - min + 1);
	}
}
