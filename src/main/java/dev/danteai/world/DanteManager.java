package dev.danteai.world;

import dev.danteai.DanteAi;
import dev.danteai.behavior.DanteBrain;
import dev.danteai.chat.ConversationService;
import dev.danteai.chat.DanteSpeaker;
import dev.danteai.chat.PromptBuilder;
import dev.danteai.config.DanteConfig;
import dev.danteai.entity.DanteEntity;
import dev.danteai.entity.ModEntities;
import dev.danteai.inventory.DanteInventory;
import dev.danteai.inventory.InventoryActions;
import dev.danteai.llm.LlmGateway;
import dev.danteai.memory.MemoryService;
import dev.danteai.memory.MemoryStore;
import dev.danteai.tools.DanteTools;
import dev.danteai.tools.ToolRegistry;
import dev.danteai.util.DanteLog;
import dev.danteai.voice.VoiceService;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Administra al único Dante del servidor: su identidad canónica, su estado persistente, su "cerebro" y los
 * servicios de conversación, memoria y voz.
 * <p>
 * Dante existe aunque no haya jugadores: su cuerpo queda guardado en el chunk donde estaba, sin forzar que se
 * mantenga cargado. Si alguien lo necesita y el cuerpo está en un chunk descargado, se "re-materializa" un cuerpo
 * nuevo cerca del jugador y el viejo se descarta automáticamente cuando su chunk vuelve a cargar. Como el
 * inventario vive en {@link DanteWorldData} y no en la entidad, nunca se duplica ni se pierde nada.
 */
public final class DanteManager {
	/** Posición de Dante para hilos de fondo (escucha por voz). */
	public record Position(Object level, double x, double y, double z) {
	}

	private final MinecraftServer server;
	private final DanteWorldData data;
	private final DanteBrain brain;
	private final DanteSpeaker speaker;
	private final MemoryService memory;
	private final VoiceService voice;
	private final ConversationService conversation;
	private final dev.danteai.chat.VisitService visit;
	private final ToolRegistry tools;
	private volatile LlmGateway llm;
	private volatile DanteConfig config;
	private volatile String personality;
	private volatile Position position;

	private final ServiceMonitor services = new ServiceMonitor(ServiceMonitor::httpProbe);
	private DanteEntity body;
	private long missingSinceTick = -1;
	private int tickCounter;
	private volatile boolean hiddenForServices;

	public DanteManager(MinecraftServer server, DanteConfig config, String personality, MemoryStore store) {
		this.server = server;
		this.config = config;
		this.personality = personality;
		this.services.setListener(DanteManager::logServices);
		this.services.configure(ServiceMonitor.targets(config), config.dante.service_check_seconds);
		if (config.dante.require_services) this.services.checkNow();
		this.data = server.overworld().getDataStorage().computeIfAbsent(DanteWorldData.FACTORY, "dante_state");
		this.data.inventory().setCapacity(config.inventory.slots);
		this.brain = new DanteBrain(this);
		this.brain.restoreNeeds(data.energy(), data.social(), data.curiosity());
		this.speaker = new DanteSpeaker(this);
		this.memory = new MemoryService(store, memorySettings(config));
		this.voice = new VoiceService(server, () -> body, config.voice);
		this.llm = new LlmGateway(config.llm);
		this.tools = DanteTools.create(this);
		this.conversation = new ConversationService(this);
		this.visit = new dev.danteai.chat.VisitService(this);
		this.memory.setSummarizer(text -> llm.enabled()
			? llm.simple(PromptBuilder.SUMMARY_SYSTEM, text, 200)
			: java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("LLM desactivado")));
		this.memory.pruneOld();
	}

	private static MemoryService.Settings memorySettings(DanteConfig c) {
		return new MemoryService.Settings(c.memory.enabled, c.memory.context_messages, c.memory.summarize_after_messages,
			c.memory.context_facts, c.memory.retention_days);
	}

	/** Aplica una configuración recargada sin reiniciar el servidor. */
	public void reload(DanteConfig newConfig, String newPersonality) {
		LlmGateway old = llm;
		this.config = newConfig;
		this.personality = newPersonality;
		this.llm = new LlmGateway(newConfig.llm);
		old.shutdown();
		voice.configure(newConfig.voice);
		services.configure(ServiceMonitor.targets(newConfig), newConfig.dante.service_check_seconds);
		services.refresh();
		memory.updateSettings(memorySettings(newConfig));
		conversation.reconfigure();
		data.inventory().setCapacity(newConfig.inventory.slots);
		if (body != null) applyIdentity(body);
	}

	public void shutdown() {
		services.shutdown();
		conversation.shutdown();
		llm.shutdown();
		voice.shutdown();
		brain.cancelTask("el servidor se detiene");
		data.setNeeds(brain.needs().energy(), brain.needs().social(), brain.needs().curiosity());
		if (body != null) recordPosition(body);
		memory.close();
	}

	// ------------------------------------------------------------------ accesos

	public MinecraftServer server() {
		return server;
	}

	public DanteConfig config() {
		return config;
	}

	public String personality() {
		return personality;
	}

	public DanteWorldData data() {
		return data;
	}

	public DanteInventory inventory() {
		return data.inventory();
	}

	public DanteBrain brain() {
		return brain;
	}

	public DanteSpeaker speaker() {
		return speaker;
	}

	public MemoryService memory() {
		return memory;
	}

	public VoiceService voice() {
		return voice;
	}

	public LlmGateway llm() {
		return llm;
	}

	public ToolRegistry tools() {
		return tools;
	}

	public dev.danteai.chat.VisitService visit() {
		return visit;
	}

	public ConversationService conversation() {
		return conversation;
	}

	/** Cuerpo cargado actualmente, o {@code null} si está en un chunk descargado o no existe. */
	public DanteEntity body() {
		if (body != null && body.isRemoved()) body = null;
		return body;
	}

	public Position position() {
		return position;
	}

	/** Dante "existe" si alguna vez se creó y no fue retirado por un administrador. */
	public boolean exists() {
		return data.danteUuid() != null && !data.removedByAdmin();
	}

	public String text(String s) {
		return s;
	}

	// ------------------------------------------------------------------ servicios requeridos

	/** {@code true} si Dante puede estar en el mundo: no se exigen servicios o todos responden. */
	public boolean servicesOk() {
		return !config.dante.require_services || services.available();
	}

	public ServiceMonitor services() {
		return services;
	}

	public String servicesStatus() {
		if (!config.dante.require_services) return "no se exigen";
		return services.available() ? "todos responden" : "no responden: " + String.join(", ", services.missing());
	}

	private static void logServices(boolean ok, List<String> missing) {
		if (ok) DanteLog.info("Los servicios de Dante responden: puede aparecer.");
		else DanteLog.warn("Servicios de Dante sin respuesta ({}): no aparecerá hasta que todos respondan.", String.join(", ", missing));
	}

	/** Retira el cuerpo mientras faltan servicios; el estado persistente (inventario, memoria) no se toca. */
	private void hideForServices(DanteEntity entity) {
		hiddenForServices = true;
		brain.cancelTask("faltan servicios");
		if (body == entity) body = null;
		entity.discard();
	}

	// ------------------------------------------------------------------ ciclo de vida de la entidad

	private boolean isCanonical(Entity e) {
		return data.danteUuid() != null && data.danteUuid().equals(e.getUUID());
	}

	/** Evento de carga de entidad: adopta al Dante canónico y descarta duplicados. */
	public void onEntityLoad(Entity entity, ServerLevel level) {
		if (!(entity instanceof DanteEntity dante)) return;
		if (data.danteUuid() == null && !data.removedByAdmin()) {
			data.setDanteUuid(dante.getUUID());
			DanteLog.info("Dante adoptado: {}", dante.getUUID());
		}
		if (!isCanonical(dante) || data.removedByAdmin()) {
			DanteLog.info("Se descartó un cuerpo duplicado o retirado de Dante ({}) en {}", dante.getUUID(), level.dimension().location());
			dante.discard();
			return;
		}
		body = dante;
		missingSinceTick = -1;
		applyIdentity(dante);
	}

	public void onEntityUnload(Entity entity, ServerLevel level) {
		if (entity instanceof DanteEntity dante && dante == body) {
			recordPosition(dante);
			body = null;
			position = null;
		}
	}

	/** Llamado por el cuerpo en cada tick de IA. */
	public void driveBody(DanteEntity entity, ServerLevel level) {
		if (!isCanonical(entity) || data.removedByAdmin()) {
			entity.discard();
			return;
		}
		if (!servicesOk()) {
			hideForServices(entity);
			return;
		}
		if (body != entity) {
			body = entity;
			applyIdentity(entity);
		}
		position = new Position(level, entity.getX(), entity.getY(), entity.getZ());
		if (entity.tickCount % 40 == 0) recordPosition(entity);
		brain.tick(entity, level);
	}

	private void recordPosition(DanteEntity e) {
		data.setLastPosition(e.level().dimension().location().toString(), e.getX(), e.getY(), e.getZ());
	}

	private void applyIdentity(DanteEntity e) {
		ChatFormatting color;
		try {
			color = ChatFormatting.valueOf(config.dante.name_color.toUpperCase(java.util.Locale.ROOT));
		} catch (IllegalArgumentException ex) {
			color = ChatFormatting.GOLD;
		}
		e.setCustomName(Component.literal(config.dante.name).withStyle(color));
		e.setCustomNameVisible(true);
		e.setInvulnerable(config.dante.invulnerable);
	}

	public void onBodyDied(DanteEntity e) {
		if (!isCanonical(e)) return;
		long delay = config.dante.respawn_delay_seconds * 20L;
		data.setRespawnAt(server.overworld().getGameTime() + delay);
		data.setDanteUuid(null);
		body = null;
		brain.cancelTask("Dante se desmayó");
		server.getPlayerList().broadcastSystemMessage(Component.literal(config.dante.name + " se desmayó... volverá en "
			+ config.dante.respawn_delay_seconds + " segundos.").withStyle(ChatFormatting.GRAY), false);
	}

	public void onBodyHurt(DanteEntity e, DamageSource source) {
		if (!isCanonical(e)) return;
		Entity attacker = source.getEntity();
		brain.onHurt(attacker != null ? attacker.position() : source.getSourcePosition() != null ? source.getSourcePosition() : e.position());
	}

	// ------------------------------------------------------------------ tick del servidor

	public void tick() {
		tickCounter++;
		if (tickCounter % 20 != 0) return;
		if (body != null && body.isRemoved()) body = null;
		conversation.tickSecond();
		visit.tickSecond();
		if (!servicesOk()) {
			if (body != null) hideForServices(body);
			else if (data.danteUuid() != null) hiddenForServices = true;
			missingSinceTick = -1;
			return;
		}
		if (body != null || data.removedByAdmin()) {
			missingSinceTick = -1;
			hiddenForServices = false;
			return;
		}
		List<ServerPlayer> players = new ArrayList<>(server.getPlayerList().getPlayers());
		players.removeIf(ServerPlayer::isSpectator);
		long now = server.overworld().getGameTime();
		if (hiddenForServices && data.respawnAt() < 0) {
			// Los servicios volvieron: reaparece de inmediato cerca de alguien.
			if (!players.isEmpty()) spawnSomewhere(players);
			return;
		}
		if (data.respawnAt() >= 0) {
			if (now >= data.respawnAt()) {
				data.setRespawnAt(-1);
				spawnSomewhere(players);
			}
			return;
		}
		if (data.danteUuid() == null) {
			if (config.dante.auto_spawn && !players.isEmpty()) spawnSomewhere(players);
			return;
		}
		// El cuerpo existe pero está en un chunk descargado.
		if (players.isEmpty()) {
			missingSinceTick = -1;
			return;
		}
		if (missingSinceTick < 0) missingSinceTick = now;
		DanteConfig.Behavior b = config.behavior;
		long lonelyTicks = b.lonely_minutes_before_seeking * 60L * 20L;
		if (b.autonomous && b.teleport_enabled && b.rematerialize_when_unloaded && now - missingSinceTick >= lonelyTicks
			&& brain.teleportOffCooldown()) {
			ServerPlayer target = players.get(server.overworld().getRandom().nextInt(players.size()));
			DanteLog.info("Dante estaba solo en un chunk descargado; va a buscar a {}", target.getGameProfile().getName());
			if (teleportNear(target)) missingSinceTick = -1;
		}
	}

	private void spawnSomewhere(List<ServerPlayer> players) {
		if (!players.isEmpty()) {
			ServerPlayer p = players.get(server.overworld().getRandom().nextInt(players.size()));
			spawnNear(p.serverLevel(), p.blockPosition());
		} else {
			ServerLevel level = server.overworld();
			BlockPos spawn = level.getSharedSpawnPos();
			spawnNear(level, level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, spawn));
		}
	}

	/** Crea un cuerpo nuevo (y canónico) cerca de una posición. El cuerpo anterior, si existía, queda obsoleto. */
	public DanteEntity spawnNear(ServerLevel level, BlockPos center) {
		BlockPos pos = SafeSpots.findNear(level, center, 2, 5, ModEntities.DANTE, level.getRandom());
		if (pos == null) pos = center;
		DanteEntity old = body();
		DanteEntity created = ModEntities.DANTE.create(level);
		if (created == null) return null;
		created.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, level.getRandom().nextFloat() * 360F, 0);
		data.setRemovedByAdmin(false);
		data.setDanteUuid(created.getUUID());
		data.setRespawnAt(-1);
		hiddenForServices = false;
		if (old != null) old.discard();
		body = null;
		applyIdentity(created);
		level.addFreshEntity(created);
		body = created;
		recordPosition(created);
		arrivalEffects(level, created);
		DanteLog.info("Dante apareció en {} {}", level.dimension().location(), pos.toShortString());
		return created;
	}

	/**
	 * Lleva a Dante cerca de un jugador: teletransporte normal si el cuerpo está cargado (también entre dimensiones)
	 * o re-materialización si el cuerpo está en un chunk descargado.
	 */
	public boolean teleportNear(ServerPlayer target) {
		ServerLevel level = target.serverLevel();
		BlockPos dest = SafeSpots.findNear(level, target.blockPosition(), 2, 4, ModEntities.DANTE, level.getRandom());
		if (dest == null) return false;
		DanteEntity current = body();
		brain.markTeleported();
		if (current == null) {
			return spawnNear(level, dest) != null;
		}
		ServerLevel from = (ServerLevel) current.level();
		departureEffects(from, current);
		current.getNavigation().stop();
		current.setDantePose(DanteEntity.POSE_STANDING);
		Vec3 pos = Vec3.atBottomCenterOf(dest);
		float yaw = (float) Math.toDegrees(Math.atan2(target.getZ() - pos.z, target.getX() - pos.x)) - 90F;
		if (from == level) {
			current.teleportTo(pos.x, pos.y, pos.z);
			current.setYRot(yaw);
			arrivalEffects(level, current);
			return true;
		}
		Entity moved = current.changeDimension(new net.minecraft.world.level.portal.DimensionTransition(level, pos, Vec3.ZERO, yaw, 0F,
			net.minecraft.world.level.portal.DimensionTransition.DO_NOTHING));
		if (moved instanceof DanteEntity dante) {
			body = dante;
			arrivalEffects(level, dante);
			return true;
		}
		return false;
	}

	private void departureEffects(ServerLevel level, DanteEntity e) {
		level.sendParticles(ParticleTypes.POOF, e.getX(), e.getY() + 0.5, e.getZ(), 12, 0.3, 0.3, 0.3, 0.02);
	}

	private void arrivalEffects(ServerLevel level, DanteEntity e) {
		level.sendParticles(ParticleTypes.POOF, e.getX(), e.getY() + 0.5, e.getZ(), 12, 0.3, 0.3, 0.3, 0.02);
		level.playSound(null, e.getX(), e.getY(), e.getZ(), SoundEvents.FOX_TELEPORT, SoundSource.NEUTRAL, 0.6F, 1.3F);
	}

	// ------------------------------------------------------------------ administración

	/** Retira a Dante del mundo (el inventario se conserva) y desactiva la aparición automática. */
	public boolean remove() {
		DanteEntity current = body();
		brain.cancelTask("retirado por un administrador");
		data.setRemovedByAdmin(true);
		if (current != null) current.discard();
		body = null;
		return current != null;
	}

	public void resetBrain() {
		brain.forceActivity(dev.danteai.behavior.Activity.IDLE);
		brain.restoreNeeds(0.8, 0.5, 0.5);
	}

	public String statusLine() {
		DanteEntity b = body();
		String where = b != null ? b.level().dimension().location() + " " + b.blockPosition().toShortString()
			: (data.danteUuid() == null ? "no existe" : "cuerpo en chunk descargado (" + data.dimension() + " "
				+ Math.round(data.x()) + ", " + Math.round(data.y()) + ", " + Math.round(data.z()) + ")");
		return where;
	}

	public ServerLevel levelOf(String dimension) {
		ResourceLocation id = ResourceLocation.tryParse(dimension);
		if (id == null) return server.overworld();
		ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
		return level == null ? server.overworld() : level;
	}

	// ------------------------------------------------------------------ interacción y objetos

	public InteractionResult onPlayerInteract(DanteEntity e, ServerPlayer player, InteractionHand hand) {
		if (!isCanonical(e)) return InteractionResult.PASS;
		ItemStack held = player.getItemInHand(hand);
		brain.attend(player, 60);
		if (!held.isEmpty() && config.inventory.enabled && config.inventory.right_click_give) {
			int before = held.getCount();
			ItemStack sample = held.copy();
			int inserted = inventory().insert(held, player.getUUID(), player.getGameProfile().getName());
			if (inserted <= 0) {
				e.whine();
				speaker.actionBar(player, config.dante.name + " tiene la mochila llena");
				return InteractionResult.SUCCESS;
			}
			java.util.Map<String, Integer> map = new java.util.LinkedHashMap<>();
			map.put(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(sample.getItem()).toString(), inserted);
			onItemsReceived(player, new InventoryActions.Transfer(inserted, map, 0));
			if (before == inserted) player.setItemInHand(hand, ItemStack.EMPTY);
			return InteractionResult.SUCCESS;
		}
		// Mano vacía: caricias.
		e.setMood(100);
		brain.needs().set(brain.needs().energy(), brain.needs().social() - 0.2, brain.needs().curiosity());
		((ServerLevel) e.level()).sendParticles(ParticleTypes.HEART, e.getX(), e.getY() + 1.2, e.getZ(), 3, 0.3, 0.2, 0.3, 0.0);
		if (e.getRandom().nextInt(3) == 0) e.bark();
		return InteractionResult.SUCCESS;
	}

	public void onItemsReceived(ServerPlayer from, InventoryActions.Transfer t) {
		if (t.total() <= 0) return;
		speaker.emote("guarda " + t.describe() + " de " + from.getGameProfile().getName() + " en su mochila", from);
		memory.remember(from.getUUID(), "event", from.getGameProfile().getName() + " te dio " + t.describe() + " para guardar", 3);
	}

	public void onItemsGiven(ServerPlayer to, InventoryActions.Transfer t) {
		if (t.total() <= 0) return;
		speaker.emote("le da " + t.describe() + " a " + to.getGameProfile().getName(), to);
		memory.remember(to.getUUID(), "event", "Le devolviste/diste " + t.describe() + " a " + to.getGameProfile().getName(), 2);
	}

	public void onItemsCollected(InventoryActions.Transfer t, UUID owner, String ownerName) {
		speaker.emote("recoge " + t.describe() + " del suelo" + (owner != null ? " (lo guarda para " + ownerName + ")" : ""), null);
	}

	public void onContainerTransfer(ServerPlayer player, boolean stored, InventoryActions.Transfer t, BlockPos pos) {
		if (t.total() == 0) {
			speaker.emote(stored ? "no pudo guardar nada en el cofre (está lleno)" : "no encontró nada para sacar del cofre", player);
			return;
		}
		speaker.emote((stored ? "guarda " : "saca ") + t.describe() + (stored ? " en el cofre" : " del cofre"), player);
		memory.remember(player.getUUID(), "event", (stored ? "Guardaste " : "Sacaste ") + t.describe()
			+ (stored ? " en el cofre de " : " del cofre de ") + pos.toShortString() + " para " + player.getGameProfile().getName(), 3);
	}
}
