package dev.danteai.behavior;

import dev.danteai.config.DanteConfig;
import dev.danteai.entity.DanteEntity;
import dev.danteai.util.DanteLog;
import dev.danteai.world.DanteManager;
import dev.danteai.world.SafeSpots;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.ai.util.LandRandomPos;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Máquina de estados de alto nivel. Vive en {@link DanteManager} (no en la entidad) y conduce al cuerpo canónico.
 * Cada tick ejecuta la actividad actual; cuando termina, elige otra con {@link BehaviorSelector} salvo que haya una
 * orden vigente de un jugador (seguir, quedarse sentado) o un encargo en curso.
 */
public final class DanteBrain {
	private final DanteManager manager;
	private final Random random = new Random();
	private final Needs needs = new Needs();

	private Activity activity = Activity.IDLE;
	private int ticksLeft;
	private UUID focusPlayer;
	private BlockPos targetPos;
	private Vec3 dangerPos;
	private DanteTask task;
	private int taskTicks;
	private String lastExplored = "";

	// Órdenes de jugadores.
	private UUID commandedFollow;
	private long commandedFollowUntil;
	private boolean commandedSit;
	private long commandedSitUntil;

	// Atención: a quién está escuchando / mirando ahora.
	private UUID attentionPlayer;
	private int attentionTicks;

	private long lastTeleportTime = -1_000_000;
	private long lastPlayerNearbyTime;
	private int tickCounter;
	private int repathCooldown;
	private int idleLookCooldown;
	private int sniffTicks;
	private int spinTicks;

	public DanteBrain(DanteManager manager) {
		this.manager = manager;
	}

	public Needs needs() {
		return needs;
	}

	public Activity activity() {
		return activity;
	}

	public String describeActivity() {
		String base = activity.displayName();
		if (task != null) return base + ": " + task.describe();
		if (activity == Activity.FOLLOWING && focusPlayer != null) {
			ServerPlayer p = manager.server().getPlayerList().getPlayer(focusPlayer);
			if (p != null) return base + " (" + p.getGameProfile().getName() + ")";
		}
		if (activity == Activity.EXPLORING && !lastExplored.isEmpty()) return base + " (" + lastExplored + ")";
		return base;
	}

	public UUID followTarget() {
		return activity == Activity.FOLLOWING ? focusPlayer : null;
	}

	public long gameTime() {
		return manager.server().overworld().getGameTime();
	}

	// ------------------------------------------------------------------ órdenes

	public void commandFollow(ServerPlayer player, int seconds) {
		commandedSit = false;
		commandedFollow = player.getUUID();
		commandedFollowUntil = gameTime() + seconds * 20L;
		cancelTask("nueva orden");
		switchTo(Activity.FOLLOWING, seconds * 20, player.getUUID());
	}

	public boolean commandStopFollowing() {
		boolean was = commandedFollow != null || activity == Activity.FOLLOWING;
		commandedFollow = null;
		if (activity == Activity.FOLLOWING) switchTo(Activity.IDLE, 60, null);
		return was;
	}

	public void commandSit(int seconds) {
		commandedFollow = null;
		commandedSit = true;
		commandedSitUntil = gameTime() + seconds * 20L;
		cancelTask("nueva orden");
		switchTo(Activity.RESTING, seconds * 20, null);
	}

	public boolean commandStand() {
		boolean was = commandedSit || activity == Activity.RESTING;
		commandedSit = false;
		DanteEntity body = manager.body();
		if (body != null) body.setDantePose(DanteEntity.POSE_STANDING);
		if (activity == Activity.RESTING) switchTo(Activity.IDLE, 40, null);
		return was;
	}

	public void commandWander() {
		commandedFollow = null;
		commandedSit = false;
		cancelTask("nueva orden");
		switchTo(Activity.WANDERING, 20 * 40, null);
	}

	public void startTask(DanteTask newTask) {
		cancelTask("reemplazado por otro encargo");
		commandedSit = false;
		task = newTask;
		taskTicks = 0;
		switchTo(Activity.INTERACTING, newTask.timeoutTicks(), null);
	}

	public void cancelTask(String reason) {
		if (task != null) {
			DanteTask t = task;
			task = null;
			t.onCancel(manager, reason);
		}
	}

	public boolean hasTask() {
		return task != null;
	}

	/** Mira a un jugador (al conversar o por orden) durante {@code ticks}. */
	public void attend(ServerPlayer player, int ticks) {
		attentionPlayer = player.getUUID();
		attentionTicks = ticks;
	}

	public void forceActivity(Activity a) {
		commandedFollow = null;
		commandedSit = false;
		cancelTask("forzado por admin");
		switchTo(a, BehaviorSelector.durationTicks(a, 30, 60, 20, 40, random), null);
	}

	public void markTeleported() {
		lastTeleportTime = gameTime();
	}

	public boolean teleportOffCooldown() {
		return gameTime() - lastTeleportTime >= manager.config().behavior.teleport_cooldown_seconds * 20L;
	}

	// ------------------------------------------------------------------ tick

	/** Gira sobre sí mismo persiguiéndose la cola. */
	public void forceSpin() {
		spinTicks = 24;
	}

	public void tick(DanteEntity body, ServerLevel level) {
		tickCounter++;
		DanteConfig cfg = manager.config();
		if (spinTicks > 0) {
			spinTicks--;
			float rot = body.getYRot() + 30.0F;
			body.setYRot(rot);
			body.yBodyRot = rot;
			body.yHeadRot = rot;
			body.getNavigation().stop();
			return;
		}

		if (attentionTicks > 0) {
			attentionTicks--;
			ServerPlayer p = attentionPlayer == null ? null : manager.server().getPlayerList().getPlayer(attentionPlayer);
			if (p != null && p.level() == level && p.distanceToSqr(body) < 32 * 32) {
				body.getLookControl().setLookAt(p, 30.0F, 30.0F);
			}
		}

		if (tickCounter % 20 == 0) {
			secondTick(body, level);
		}
		if (tickCounter % 5 == 0 && activity != Activity.FLEEING) {
			Vec3 danger = detectDanger(body, level);
			if (danger != null) {
				dangerPos = danger;
				body.setDantePose(DanteEntity.POSE_STANDING);
				switchTo(Activity.FLEEING, BehaviorSelector.durationTicks(Activity.FLEEING, 0, 0, 0, 0, random), null);
			}
		}

		ticksLeft--;
		boolean done = switch (activity) {
			case WANDERING -> tickWander(body, level, cfg);
			case FOLLOWING -> tickFollow(body, level, cfg);
			case RESTING -> tickRest(body, level);
			case SOCIALIZING -> tickSocialize(body, level);
			case EXPLORING -> tickExplore(body, level);
			case TELEPORTING -> tickTeleport(body, level);
			case INTERACTING -> tickTask(body, level);
			case FLEEING -> tickFlee(body);
			case IDLE -> tickIdle(body, level);
		};
		if (done || ticksLeft <= 0) {
			chooseNext(body, level);
		}
	}

	private void secondTick(DanteEntity body, ServerLevel level) {
		boolean moving = !body.getNavigation().isDone();
		List<ServerPlayer> near = nearbyPlayers(body, level, manager.config().behavior.social_radius);
		if (!near.isEmpty()) lastPlayerNearbyTime = gameTime();
		needs.tickSecond(activity, moving, !near.isEmpty());
		int mood = (int) Math.round(100 * (0.35 + 0.35 * needs.energy() + 0.3 * (1 - needs.social())));
		if (activity == Activity.SOCIALIZING || activity == Activity.FOLLOWING) mood += 10;
		body.setMood(mood);
		body.setFlag(DanteEntity.FLAG_PANTING, moving && (needs.energy() < 0.45 || activity == Activity.FOLLOWING));
		body.setFlag(DanteEntity.FLAG_EXCITED, activity == Activity.SOCIALIZING || attentionTicks > 0);
		if (tickCounter % (20 * 30) == 0) {
			manager.data().setNeeds(needs.energy(), needs.social(), needs.curiosity());
		}
		// Juguetes: si ve un hueso o un palo cerca, a veces lo agarra por su cuenta.
		if (activity == Activity.WANDERING || activity == Activity.EXPLORING || activity == Activity.IDLE) {
			maybePickUpToy(body, level);
		}
	}

	private void chooseNext(DanteEntity body, ServerLevel level) {
		long now = gameTime();
		if (task != null) {
			DanteTask t = task;
			task = null;
			t.onCancel(manager, "se acabó el tiempo");
		}
		if (commandedFollow != null && now < commandedFollowUntil) {
			ServerPlayer p = manager.server().getPlayerList().getPlayer(commandedFollow);
			if (p != null) {
				switchTo(Activity.FOLLOWING, (int) Math.min(Integer.MAX_VALUE, commandedFollowUntil - now), commandedFollow);
				return;
			}
		}
		commandedFollow = null;
		if (commandedSit && now < commandedSitUntil) {
			switchTo(Activity.RESTING, (int) (commandedSitUntil - now), null);
			return;
		}
		commandedSit = false;
		DanteConfig.Behavior b = manager.config().behavior;
		if (!b.autonomous) {
			switchTo(Activity.IDLE, 100, null);
			return;
		}
		BehaviorSelector.Situation situation = situation(body, level);
		Activity next = BehaviorSelector.choose(situation, needs, weights(b), random);
		int duration = BehaviorSelector.durationTicks(next, b.follow_min_seconds, b.follow_max_seconds, b.rest_min_seconds,
			b.rest_max_seconds, random);
		UUID focus = null;
		if (next == Activity.FOLLOWING || next == Activity.SOCIALIZING) {
			List<ServerPlayer> near = nearbyPlayers(body, level, b.social_radius);
			if (near.isEmpty()) {
				next = Activity.WANDERING;
			} else {
				focus = near.get(random.nextInt(near.size())).getUUID();
			}
		}
		DanteLog.debug("Nueva actividad {} ({} s) energía={} social={} curiosidad={}", next, duration / 20,
			fmt(needs.energy()), fmt(needs.social()), fmt(needs.curiosity()));
		switchTo(next, duration, focus);
	}

	private static String fmt(double v) {
		return String.format(java.util.Locale.ROOT, "%.2f", v);
	}

	private void switchTo(Activity next, int duration, UUID focus) {
		DanteEntity body = manager.body();
		if (body != null) {
			if (next != Activity.RESTING) body.setDantePose(DanteEntity.POSE_STANDING);
			if (next != activity) body.getNavigation().stop();
		}
		activity = next;
		ticksLeft = Math.max(1, duration);
		focusPlayer = focus;
		targetPos = null;
		repathCooldown = 0;
		sniffTicks = 0;
	}

	private BehaviorSelector.Weights weights(DanteConfig.Behavior b) {
		return new BehaviorSelector.Weights(b.wander_enabled, b.follow_enabled, b.explore_enabled, b.socialize_enabled, b.rest_enabled,
			b.teleport_enabled, b.follow_probability, b.idle_probability, b.explore_probability, b.socialize_probability,
			b.ignore_probability, b.teleport_distance, b.lonely_minutes_before_seeking * 60 * 20);
	}

	private BehaviorSelector.Situation situation(DanteEntity body, ServerLevel level) {
		DanteConfig.Behavior b = manager.config().behavior;
		List<ServerPlayer> near = nearbyPlayers(body, level, b.social_radius);
		double nearest = Double.POSITIVE_INFINITY;
		int inDimension = 0;
		for (ServerPlayer p : level.players()) {
			if (p.isSpectator()) continue;
			inDimension++;
			nearest = Math.min(nearest, p.distanceTo(body));
		}
		int online = (int) manager.server().getPlayerList().getPlayers().stream().filter(p -> !p.isSpectator()).count();
		return new BehaviorSelector.Situation(level.isNight(), level.isRaining(), near.size(), inDimension, online, nearest,
			findInterestingSpot(body, level, false) != null, teleportOffCooldown(), gameTime() - lastPlayerNearbyTime, false);
	}

	// ------------------------------------------------------------------ actividades

	private boolean tickWander(DanteEntity body, ServerLevel level, DanteConfig cfg) {
		if (sniffTicks > 0) {
			sniffTicks--;
			return false;
		}
		if (body.getNavigation().isDone()) {
			if (targetPos != null && random.nextInt(3) == 0) {
				sniffTicks = 20 + random.nextInt(40);
				lookAtGround(body);
				targetPos = null;
				return false;
			}
			Vec3 pos = LandRandomPos.getPos(body, cfg.behavior.wander_radius, 6);
			if (pos != null && SafeSpots.isSafeStandingSpot(level, BlockPos.containing(pos), body.getType())) {
				targetPos = BlockPos.containing(pos);
				body.getNavigation().moveTo(pos.x, pos.y, pos.z, 0.9);
			}
		}
		return false;
	}

	private boolean tickFollow(DanteEntity body, ServerLevel level, DanteConfig cfg) {
		ServerPlayer target = focusPlayer == null ? null : manager.server().getPlayerList().getPlayer(focusPlayer);
		if (target == null || target.isSpectator()) return true;
		boolean commanded = focusPlayer.equals(commandedFollow);
		if (target.level() != level) {
			if (cfg.behavior.teleport_enabled && cfg.behavior.teleport_across_dimensions && teleportOffCooldown()) {
				manager.teleportNear(target);
			}
			return !commanded;
		}
		double dist = body.distanceTo(target);
		double teleportAt = commanded ? Math.min(cfg.behavior.teleport_distance, 32) : cfg.behavior.teleport_distance;
		if (dist > teleportAt && cfg.behavior.teleport_enabled && teleportOffCooldown()) {
			manager.teleportNear(target);
			return false;
		}
		if (!commanded && dist > cfg.behavior.teleport_distance) {
			return true;
		}
		double stop = cfg.behavior.follow_stop_distance;
		if (dist > stop + 0.5) {
			if (--repathCooldown <= 0) {
				repathCooldown = 10;
				body.getNavigation().moveTo(target, dist > 10 ? 1.4 : 1.1);
			}
		} else {
			body.getNavigation().stop();
			body.getLookControl().setLookAt(target, 20.0F, 20.0F);
			// Si el jugador se queda quieto un rato, Dante se sienta a su lado.
			if (target.getDeltaMovement().horizontalDistanceSqr() < 1.0E-4 && random.nextInt(400) == 0) {
				body.setDantePose(DanteEntity.POSE_SITTING);
			}
		}
		if (dist > stop + 2 && body.getDantePose() != DanteEntity.POSE_STANDING) {
			body.setDantePose(DanteEntity.POSE_STANDING);
		}
		return false;
	}

	private boolean tickRest(DanteEntity body, ServerLevel level) {
		if (body.getDantePose() == DanteEntity.POSE_STANDING) {
			body.getNavigation().stop();
			body.setDantePose(DanteEntity.POSE_SITTING);
		}
		// Si está muy cansado o es de noche, se duerme después de un rato sentado.
		if (body.getDantePose() == DanteEntity.POSE_SITTING && !commandedSit && random.nextInt(200) == 0
			&& (needs.energy() < 0.35 || level.isNight())) {
			body.setDantePose(DanteEntity.POSE_SLEEPING);
		}
		if (body.getDantePose() == DanteEntity.POSE_SLEEPING && level.getGameTime() % 40 == 0) {
			level.sendParticles(ParticleTypes.CLOUD, body.getX(), body.getY() + 0.9, body.getZ(), 1, 0.1, 0.05, 0.1, 0.0);
		}
		if (body.getDantePose() == DanteEntity.POSE_SITTING && random.nextInt(120) == 0) {
			idleLook(body, level);
		}
		if (ticksLeft <= 1) body.setDantePose(DanteEntity.POSE_STANDING);
		return false;
	}

	private boolean tickSocialize(DanteEntity body, ServerLevel level) {
		ServerPlayer target = focusPlayer == null ? null : manager.server().getPlayerList().getPlayer(focusPlayer);
		if (target == null || target.level() != level || target.distanceTo(body) > manager.config().behavior.social_radius * 1.5) {
			return true;
		}
		double dist = body.distanceTo(target);
		if (dist > 2.6) {
			if (--repathCooldown <= 0) {
				repathCooldown = 15;
				body.setDantePose(DanteEntity.POSE_STANDING);
				body.getNavigation().moveTo(target, 1.0);
			}
		} else {
			body.getNavigation().stop();
			body.getLookControl().setLookAt(target, 30.0F, 30.0F);
			int r = random.nextInt(300);
			if (r == 0) body.bark();
			else if (r == 1) body.setDantePose(DanteEntity.POSE_SITTING);
			else if (r == 2) body.getJumpControl().jump();
			else if (r == 3 && level.getGameTime() % 2 == 0) {
				level.sendParticles(ParticleTypes.HEART, body.getX(), body.getY() + 1.2, body.getZ(), 1, 0.2, 0.1, 0.2, 0.0);
			}
		}
		return false;
	}

	private boolean tickExplore(DanteEntity body, ServerLevel level) {
		if (sniffTicks > 0) {
			sniffTicks--;
			lookAtGround(body);
			if (sniffTicks == 0) {
				targetPos = null;
				return random.nextInt(2) == 0;
			}
			return false;
		}
		if (targetPos == null) {
			BlockPos spot = findInterestingSpot(body, level, true);
			if (spot == null) {
				Vec3 pos = LandRandomPos.getPos(body, manager.config().behavior.explore_radius, 5);
				if (pos == null) return true;
				spot = BlockPos.containing(pos);
				lastExplored = "un rincón";
			}
			targetPos = spot;
			body.getNavigation().moveTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, 1.0);
			return false;
		}
		if (body.position().distanceTo(Vec3.atBottomCenterOf(targetPos)) < 2.0 || body.getNavigation().isDone()) {
			sniffTicks = 30 + random.nextInt(50);
			if (random.nextInt(4) == 0) body.bark();
		}
		return false;
	}

	private boolean tickTeleport(DanteEntity body, ServerLevel level) {
		List<ServerPlayer> candidates = new ArrayList<>(manager.server().getPlayerList().getPlayers());
		candidates.removeIf(ServerPlayer::isSpectator);
		if (candidates.isEmpty()) return true;
		ServerPlayer target = candidates.get(random.nextInt(candidates.size()));
		if (manager.teleportNear(target)) {
			// Al llegar, saluda y se queda con esa persona un rato.
			switchTo(random.nextBoolean() ? Activity.SOCIALIZING : Activity.FOLLOWING, 20 * (20 + random.nextInt(40)), target.getUUID());
			return false;
		}
		return true;
	}

	private boolean tickTask(DanteEntity body, ServerLevel level) {
		if (task == null) return true;
		taskTicks++;
		DanteTask.Status status;
		try {
			status = task.tick(body, level, manager);
		} catch (RuntimeException e) {
			DanteLog.error("Error en encargo " + task.describe(), e);
			status = DanteTask.Status.FAILURE;
		}
		if (status != DanteTask.Status.RUNNING) {
			task = null;
			return true;
		}
		return false;
	}

	private boolean tickFlee(DanteEntity body) {
		if (body.getNavigation().isDone()) {
			Vec3 away = dangerPos == null ? DefaultRandomPos.getPos(body, 8, 4) : DefaultRandomPos.getPosAway(body, 10, 5, dangerPos);
			if (away != null) body.getNavigation().moveTo(away.x, away.y, away.z, 1.5);
		}
		return false;
	}

	private boolean tickIdle(DanteEntity body, ServerLevel level) {
		if (--idleLookCooldown <= 0) {
			idleLookCooldown = 30 + random.nextInt(60);
			idleLook(body, level);
		}
		return false;
	}

	// ------------------------------------------------------------------ utilidades

	private void idleLook(DanteEntity body, ServerLevel level) {
		List<ServerPlayer> near = nearbyPlayers(body, level, 10);
		if (!near.isEmpty() && random.nextInt(3) > 0) {
			body.getLookControl().setLookAt(near.get(random.nextInt(near.size())));
		} else {
			double angle = random.nextDouble() * Math.PI * 2;
			body.getLookControl().setLookAt(body.getX() + Math.cos(angle) * 5, body.getEyeY() + random.nextGaussian(), body.getZ() + Math.sin(angle) * 5);
		}
	}

	private void lookAtGround(DanteEntity body) {
		Vec3 look = body.getLookAngle();
		body.getLookControl().setLookAt(body.getX() + look.x, body.getY() - 0.5, body.getZ() + look.z);
	}

	public List<ServerPlayer> nearbyPlayers(DanteEntity body, ServerLevel level, double radius) {
		List<ServerPlayer> out = new ArrayList<>();
		for (ServerPlayer p : level.players()) {
			if (!p.isSpectator() && p.isAlive() && p.distanceToSqr(body) <= radius * radius) out.add(p);
		}
		out.sort(Comparator.comparingDouble(p -> p.distanceToSqr(body)));
		return out;
	}

	/** Busca algo curioso: flores, cultivos, camas, campanas, animales, objetos en el suelo. */
	private BlockPos findInterestingSpot(DanteEntity body, ServerLevel level, boolean remember) {
		int r = manager.config().behavior.explore_radius;
		AABB box = body.getBoundingBox().inflate(r, 4, r);
		List<Entity> entities = level.getEntities(body, box, e -> e instanceof Animal || e instanceof ItemEntity);
		if (!entities.isEmpty() && random.nextInt(3) == 0) {
			Entity e = entities.get(random.nextInt(entities.size()));
			if (remember) lastExplored = e instanceof ItemEntity item ? "un objeto en el suelo (" + item.getItem().getHoverName().getString() + ")"
				: e.getType().getDescription().getString();
			return e.blockPosition();
		}
		BlockPos origin = body.blockPosition();
		for (int i = 0; i < 40; i++) {
			BlockPos p = origin.offset(random.nextInt(2 * r + 1) - r, random.nextInt(7) - 3, random.nextInt(2 * r + 1) - r);
			if (!level.isLoaded(p)) continue;
			BlockState s = level.getBlockState(p);
			String what = null;
			if (s.is(BlockTags.FLOWERS)) what = "unas flores";
			else if (s.is(BlockTags.CROPS)) what = "un cultivo";
			else if (s.is(BlockTags.BEDS)) what = "una cama";
			else if (s.is(Blocks.BELL)) what = "una campana";
			else if (s.is(Blocks.CHEST) || s.is(Blocks.BARREL)) what = "un cofre";
			else if (s.is(Blocks.WATER) && level.getBlockState(p.above()).isAir()) what = "un charco de agua";
			else if (s.is(Blocks.SWEET_BERRY_BUSH)) continue;
			if (what != null) {
				if (remember) lastExplored = what;
				return p;
			}
		}
		return null;
	}

	private void maybePickUpToy(DanteEntity body, ServerLevel level) {
		List<String> toys = manager.config().inventory.autonomous_pickup;
		if (toys.isEmpty() || !manager.config().inventory.enabled || task != null || random.nextInt(4) != 0) return;
		List<ItemEntity> items = level.getEntitiesOfClass(ItemEntity.class, body.getBoundingBox().inflate(6, 2, 6),
			item -> !item.hasPickUpDelay() && toys.contains(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item.getItem().getItem()).toString()));
		if (items.isEmpty() || manager.inventory().freeSlots() == 0) return;
		startTask(new Tasks.Collect(items.getFirst(), null));
	}

	private Vec3 detectDanger(DanteEntity body, ServerLevel level) {
		if (body.isInLava() || body.isOnFire()) return body.position().add(random.nextGaussian(), 0, random.nextGaussian());
		if (body.hurtTime > 0 && body.getLastHurtByMob() != null) return body.getLastHurtByMob().position();
		List<Monster> monsters = level.getEntitiesOfClass(Monster.class, body.getBoundingBox().inflate(5, 3, 5),
			m -> m.isAlive() && (m instanceof Creeper c ? c.getSwellDir() > 0 || c.distanceTo(body) < 3.5 : m.getTarget() == body));
		if (!monsters.isEmpty()) return monsters.getFirst().position();
		BlockState feet = level.getBlockState(body.blockPosition());
		if (SafeSpots.isDangerousBlock(feet)) return body.position();
		return null;
	}

	public void onHurt(Vec3 source) {
		dangerPos = source;
		if (activity != Activity.FLEEING) {
			commandedSit = false;
			switchTo(Activity.FLEEING, 60, null);
		}
	}

	public void restoreNeeds(double energy, double social, double curiosity) {
		needs.set(energy, social, curiosity);
	}
}
