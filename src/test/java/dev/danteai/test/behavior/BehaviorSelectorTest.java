package dev.danteai.test.behavior;

import dev.danteai.behavior.Activity;
import dev.danteai.behavior.BehaviorSelector;
import dev.danteai.behavior.BehaviorSelector.Situation;
import dev.danteai.behavior.BehaviorSelector.Weights;
import dev.danteai.behavior.Needs;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BehaviorSelectorTest {
	private static Weights weights(boolean teleport) {
		return new Weights(true, true, true, true, true, teleport, 0.25, 0.15, 0.20, 0.20, 0.10, 80, 20 * 60 * 8);
	}

	private static Situation situation(boolean night, int nearby, double nearest, long ticksAlone, boolean danger, boolean teleportReady) {
		return new Situation(night, false, nearby, nearby > 0 ? nearby : 1, Math.max(1, nearby), nearest, false, teleportReady, ticksAlone, danger);
	}

	private static Map<Activity, Integer> sample(Situation s, Needs n, Weights w, int count) {
		Random random = new Random(42);
		Map<Activity, Integer> counts = new EnumMap<>(Activity.class);
		for (int i = 0; i < count; i++) counts.merge(BehaviorSelector.choose(s, n, w, random), 1, Integer::sum);
		return counts;
	}

	@Test
	void dangerAlwaysMeansFleeing() {
		Map<Activity, Integer> counts = sample(situation(false, 2, 5, 0, true, true), new Needs(), weights(true), 200);
		assertEquals(200, counts.get(Activity.FLEEING));
	}

	@Test
	void neverFollowsOrSocializesWithoutNearbyPlayers() {
		Map<Activity, Integer> counts = sample(situation(false, 0, 200, 0, false, false), new Needs(), weights(true), 2000);
		assertFalse(counts.containsKey(Activity.FOLLOWING));
		assertFalse(counts.containsKey(Activity.SOCIALIZING));
		assertTrue(counts.getOrDefault(Activity.WANDERING, 0) > 0);
	}

	@Test
	void seeksFarAwayPlayersOnlyWhenAllowedAndLonely() {
		Weights w = weights(true);
		long lonely = 20L * 60 * 10;
		Map<Activity, Integer> counts = sample(situation(false, 0, 500, lonely, false, true), new Needs(), w, 1000);
		assertTrue(counts.getOrDefault(Activity.TELEPORTING, 0) > 400, "con jugadores lejos y soledad suele ir a buscarlos");

		assertFalse(sample(situation(false, 0, 500, lonely, false, true), new Needs(), weights(false), 1000).containsKey(Activity.TELEPORTING),
			"desactivado en configuración = nunca");
		assertFalse(sample(situation(false, 0, 500, lonely, false, false), new Needs(), w, 1000).containsKey(Activity.TELEPORTING),
			"respeta el cooldown");
		assertFalse(sample(situation(false, 0, 500, 100, false, true), new Needs(), w, 1000).containsKey(Activity.TELEPORTING),
			"no se teletransporta si recién estuvo con alguien");
		assertFalse(sample(situation(false, 1, 10, lonely, false, true), new Needs(), w, 1000).containsKey(Activity.TELEPORTING),
			"no lo hace si hay alguien cerca");
	}

	@Test
	void tiredAtNightRestsMuchMore() {
		Needs rested = new Needs();
		rested.set(1.0, 0.5, 0.5);
		Needs tired = new Needs();
		tired.set(0.1, 0.5, 0.5);
		int dayRested = sample(situation(false, 1, 5, 0, false, false), rested, weights(true), 4000).getOrDefault(Activity.RESTING, 0);
		int nightTired = sample(situation(true, 1, 5, 0, false, false), tired, weights(true), 4000).getOrDefault(Activity.RESTING, 0);
		assertTrue(nightTired > dayRested * 2, "noche y cansancio: " + nightTired + " vs " + dayRested);
	}

	@Test
	void lonelyDanteSeeksCompany() {
		Needs lonely = new Needs();
		lonely.set(0.8, 1.0, 0.5);
		Needs satisfied = new Needs();
		satisfied.set(0.8, 0.0, 0.5);
		Situation s = situation(false, 2, 5, 0, false, false);
		int a = sample(s, lonely, weights(true), 4000).getOrDefault(Activity.FOLLOWING, 0);
		int b = sample(s, satisfied, weights(true), 4000).getOrDefault(Activity.FOLLOWING, 0);
		assertTrue(a > b);
	}

	@Test
	void producesVariedBehaviour() {
		Map<Activity, Integer> counts = sample(situation(false, 1, 6, 0, false, false), new Needs(), weights(true), 3000);
		assertTrue(counts.size() >= 5, "al menos 5 actividades distintas: " + counts);
	}

	@Test
	void durationsRespectConfiguredRanges() {
		Random r = new Random(1);
		for (int i = 0; i < 500; i++) {
			int follow = BehaviorSelector.durationTicks(Activity.FOLLOWING, 30, 180, 15, 60, r);
			assertTrue(follow >= 30 * 20 && follow <= 180 * 20);
			int rest = BehaviorSelector.durationTicks(Activity.RESTING, 30, 180, 15, 60, r);
			assertTrue(rest >= 15 * 20 && rest <= 60 * 20);
		}
	}

	@Test
	void needsEvolveWithActivity() {
		Needs n = new Needs();
		n.set(0.5, 0.5, 0.5);
		for (int i = 0; i < 60; i++) n.tickSecond(Activity.RESTING, false, false);
		assertTrue(n.energy() > 0.5);
		assertTrue(n.social() > 0.5, "solo sube la necesidad social");
		for (int i = 0; i < 60; i++) n.tickSecond(Activity.EXPLORING, true, true);
		assertTrue(n.curiosity() < 0.5);
		for (int i = 0; i < 10000; i++) n.tickSecond(Activity.FOLLOWING, true, true);
		assertTrue(n.energy() >= 0 && n.social() >= 0, "siempre dentro de [0,1]");
	}
}
