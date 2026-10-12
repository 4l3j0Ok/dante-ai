package dev.danteai.entity;

import dev.danteai.DanteAi;
import dev.danteai.world.DanteManager;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.PathType;

/**
 * Cuerpo de Dante en el mundo. La lógica de decisiones no vive acá sino en {@link DanteManager}, que conduce al
 * cuerpo canónico: así el "cerebro" sobrevive a cambios de dimensión, reapariciones y descargas de chunks.
 * Esta clase se encarga de lo físico (navegación segura, sonidos, persistencia de la entidad) y de sincronizar
 * con los clientes el estado necesario para las animaciones.
 */
public class DanteEntity extends PathfinderMob {
	public static final int POSE_STANDING = 0;
	public static final int POSE_SITTING = 1;
	public static final int POSE_SLEEPING = 2;

	public static final byte FLAG_LISTENING = 1;
	public static final byte FLAG_SPEAKING = 2;
	public static final byte FLAG_PANTING = 4;
	public static final byte FLAG_EXCITED = 8;

	/** Evento de entidad (cliente): sacudirse como perro mojado. */
	public static final byte EVENT_SHAKE = 70;
	/** Evento de entidad (cliente): ladrido con salto de cabeza. */
	public static final byte EVENT_BARK = 71;

	private static final EntityDataAccessor<Integer> DATA_POSE = SynchedEntityData.defineId(DanteEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Byte> DATA_FLAGS = SynchedEntityData.defineId(DanteEntity.class, EntityDataSerializers.BYTE);
	/** Felicidad 0-100: controla la velocidad del movimiento de cola. */
	private static final EntityDataAccessor<Integer> DATA_MOOD = SynchedEntityData.defineId(DanteEntity.class, EntityDataSerializers.INT);

	private int speakingTicks;
	private int listeningTicks;

	// Estado de animación solo del cliente.
	private float sitProgress;
	private float sitProgressO;
	private float sleepProgress;
	private float sleepProgressO;
	private float tiltProgress;
	private float tiltProgressO;
	private int shakeTicks;
	private int barkTicks;

	public DanteEntity(EntityType<? extends DanteEntity> type, Level level) {
		super(type, level);
		this.setPathfindingMalus(PathType.LAVA, -1.0F);
		this.setPathfindingMalus(PathType.DAMAGE_FIRE, -1.0F);
		this.setPathfindingMalus(PathType.DANGER_FIRE, 16.0F);
		this.setPathfindingMalus(PathType.DAMAGE_OTHER, -1.0F);
		this.setPathfindingMalus(PathType.DANGER_OTHER, 16.0F);
		this.setPathfindingMalus(PathType.POWDER_SNOW, -1.0F);
		this.setPathfindingMalus(PathType.WATER, 4.0F);
		this.setPathfindingMalus(PathType.WATER_BORDER, 2.0F);
		this.setPathfindingMalus(PathType.STICKY_HONEY, 16.0F);
		this.setPersistenceRequired();
		this.setCanPickUpLoot(false);
	}

	public static AttributeSupplier.Builder createAttributes() {
		return PathfinderMob.createMobAttributes()
			.add(Attributes.MAX_HEALTH, 40.0)
			.add(Attributes.MOVEMENT_SPEED, 0.3)
			.add(Attributes.FOLLOW_RANGE, 48.0)
			.add(Attributes.STEP_HEIGHT, 1.0);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		super.defineSynchedData(builder);
		builder.define(DATA_POSE, POSE_STANDING);
		builder.define(DATA_FLAGS, (byte) 0);
		builder.define(DATA_MOOD, 70);
	}

	@Override
	protected void registerGoals() {
		// Nadar es lo único reactivo que maneja el sistema de goals vanilla; el resto lo decide el cerebro.
		this.goalSelector.addGoal(0, new FloatGoal(this));
	}

	@Override
	protected void customServerAiStep() {
		super.customServerAiStep();
		ServerLevel level = (ServerLevel) level();
		DanteManager manager = DanteAi.manager();
		if (manager != null) {
			manager.driveBody(this, level);
		}
		if (speakingTicks > 0 && --speakingTicks == 0) setFlag(FLAG_SPEAKING, false);
		if (listeningTicks > 0 && --listeningTicks == 0) setFlag(FLAG_LISTENING, false);
	}

	@Override
	public void tick() {
		super.tick();
		if (level().isClientSide()) {
			clientAnimationTick();
		}
	}

	private void clientAnimationTick() {
		sitProgressO = sitProgress;
		sleepProgressO = sleepProgress;
		tiltProgressO = tiltProgress;
		int pose = getDantePose();
		sitProgress = approach(sitProgress, pose == POSE_SITTING ? 1 : 0, 0.15F);
		sleepProgress = approach(sleepProgress, pose == POSE_SLEEPING ? 1 : 0, 0.08F);
		tiltProgress = approach(tiltProgress, hasFlag(FLAG_LISTENING) ? 1 : 0, 0.2F);
		if (shakeTicks > 0) shakeTicks--;
		if (barkTicks > 0) barkTicks--;
	}

	private static float approach(float value, float target, float step) {
		return value < target ? Math.min(target, value + step) : Math.max(target, value - step);
	}

	@Override
	public void handleEntityEvent(byte id) {
		if (id == EVENT_SHAKE) {
			shakeTicks = 40;
		} else if (id == EVENT_BARK) {
			barkTicks = 8;
		} else {
			super.handleEntityEvent(id);
		}
	}

	// --- Interacción ---

	@Override
	protected InteractionResult mobInteract(Player player, InteractionHand hand) {
		if (player instanceof ServerPlayer serverPlayer && level() instanceof ServerLevel) {
			DanteManager manager = DanteAi.manager();
			if (manager != null) {
				return manager.onPlayerInteract(this, serverPlayer, hand);
			}
		}
		return InteractionResult.SUCCESS;
	}

	// --- Persistencia y ciclo de vida ---

	@Override
	public boolean removeWhenFarAway(double distSqr) {
		return false;
	}

	@Override
	public boolean requiresCustomPersistence() {
		return true;
	}

	@Override
	public boolean isPushable() {
		return getDantePose() == POSE_STANDING;
	}

	@Override
	public void die(DamageSource source) {
		super.die(source);
		if (level() instanceof ServerLevel) {
			DanteManager manager = DanteAi.manager();
			if (manager != null) manager.onBodyDied(this);
		}
	}

	@Override
	public boolean hurt(DamageSource source, float damage) {
		boolean hurt = super.hurt(source, damage);
		DanteManager manager = DanteAi.manager();
		if (manager != null) manager.onBodyHurt(this, source);
		return hurt;
	}

	// --- Sonidos: ladridos "grandes", acordes a un labrador ---

	@Override
	protected SoundEvent getAmbientSound() {
		if (getDantePose() == POSE_SLEEPING) return null;
		return hasFlag(FLAG_PANTING) ? SoundEvents.WOLF_PANT : SoundEvents.WOLF_AMBIENT;
	}

	@Override
	protected SoundEvent getHurtSound(DamageSource source) {
		return SoundEvents.WOLF_HURT;
	}

	@Override
	protected SoundEvent getDeathSound() {
		return SoundEvents.WOLF_DEATH;
	}

	@Override
	public int getAmbientSoundInterval() {
		return 400;
	}

	public void bark() {
		playSound(SoundEvents.WOLF_GROWL, 1.0F, 0.9F + random.nextFloat() * 0.2F);
		level().broadcastEntityEvent(this, EVENT_BARK);
	}

	public void whine() {
		playSound(SoundEvents.WOLF_WHINE, 0.8F, 1.0F);
	}

	public void shake() {
		playSound(SoundEvents.WOLF_SHAKE, 0.8F, 1.0F);
		level().broadcastEntityEvent(this, EVENT_SHAKE);
	}

	// --- Estado sincronizado ---

	public int getDantePose() {
		return entityData.get(DATA_POSE);
	}

	public void setDantePose(int pose) {
		if (getDantePose() != pose) {
			entityData.set(DATA_POSE, pose);
			if (pose != POSE_STANDING) getNavigation().stop();
		}
	}

	public boolean hasFlag(byte flag) {
		return (entityData.get(DATA_FLAGS) & flag) != 0;
	}

	public void setFlag(byte flag, boolean value) {
		byte current = entityData.get(DATA_FLAGS);
		byte next = (byte) (value ? current | flag : current & ~flag);
		if (next != current) entityData.set(DATA_FLAGS, next);
	}

	public int getMood() {
		return entityData.get(DATA_MOOD);
	}

	public void setMood(int mood) {
		entityData.set(DATA_MOOD, Mth.clamp(mood, 0, 100));
	}

	/** Marca que Dante está hablando (la boca se mueve) durante el tiempo aproximado de lectura. */
	public void startSpeaking(int ticks) {
		speakingTicks = Math.max(speakingTicks, ticks);
		setFlag(FLAG_SPEAKING, true);
	}

	/** Inclina la cabeza como escuchando. */
	public void startListening(int ticks) {
		listeningTicks = Math.max(listeningTicks, ticks);
		setFlag(FLAG_LISTENING, true);
	}

	public void stopListening() {
		listeningTicks = 0;
		setFlag(FLAG_LISTENING, false);
	}

	// --- Lectura para el renderizador (cliente) ---

	public float getSitProgress(float partialTick) {
		return Mth.lerp(partialTick, sitProgressO, sitProgress);
	}

	public float getSleepProgress(float partialTick) {
		return Mth.lerp(partialTick, sleepProgressO, sleepProgress);
	}

	public float getTiltProgress(float partialTick) {
		return Mth.lerp(partialTick, tiltProgressO, tiltProgress);
	}

	public float getShakeProgress(float partialTick) {
		return shakeTicks <= 0 ? 0 : (40 - shakeTicks + partialTick) / 40.0F;
	}

	public float getBarkProgress(float partialTick) {
		return barkTicks <= 0 ? 0 : (8 - barkTicks + partialTick) / 8.0F;
	}
}
