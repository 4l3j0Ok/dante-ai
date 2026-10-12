package dev.danteai.tools;

import com.google.gson.JsonObject;
import dev.danteai.behavior.Tasks;
import dev.danteai.chat.WorldSnapshot;
import dev.danteai.config.DanteConfig;
import dev.danteai.entity.DanteEntity;
import dev.danteai.inventory.DanteInventory;
import dev.danteai.inventory.InventoryActions;
import dev.danteai.inventory.ItemMatcher;
import dev.danteai.inventory.OwnedStack;
import dev.danteai.memory.MemoryStore;
import dev.danteai.world.ContainerAccess;
import dev.danteai.world.DanteManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/** Herramientas disponibles para el modelo. */
public final class DanteTools {
	private DanteTools() {
	}

	@FunctionalInterface
	interface Body {
		String run(ToolContext ctx, JsonObject args) throws ToolException;
	}

	record Simple(String name, String description, JsonObject parameters, int cooldownTicks, int maxPerTurn, Body body) implements DanteTool {
		@Override
		public String execute(ToolContext ctx, JsonObject args) throws ToolException {
			return body.run(ctx, args);
		}
	}

	private static DanteTool tool(String name, String description, JsonObject schema, Body body) {
		return new Simple(name, description, schema, 0, 3, body);
	}

	private static DanteTool tool(String name, String description, JsonObject schema, int cooldownTicks, int maxPerTurn, Body body) {
		return new Simple(name, description, schema, cooldownTicks, maxPerTurn, body);
	}

	private static JsonObject playerSchema(String description) {
		JsonObject p = Args.props();
		p.add("player", Args.stringProp(description));
		return Args.schema(p, "player");
	}

	private static JsonObject noArgs() {
		return Args.schema(Args.props());
	}

	public static ToolRegistry create(DanteManager manager) {
		ToolRegistry r = new ToolRegistry(() -> manager.server().overworld().getGameTime());

		// ---------------------------------------------------------------- movimiento
		r.register(tool("follow_player", "Seguir a un jugador durante varios minutos (por ejemplo cuando te dicen 'vení conmigo').",
			playerSchema("Nombre exacto del jugador a seguir"), (ctx, a) -> {
				ServerPlayer p = ctx.resolvePlayer(Args.string(a, "player", true, 32));
				int seconds = manager.config().behavior.requested_follow_seconds;
				manager.brain().commandFollow(p, seconds);
				return "Ahora seguís a " + p.getGameProfile().getName() + " durante " + seconds / 60 + " minutos (o hasta que te digan que pares).";
			}));
		r.register(tool("stop_following", "Dejar de seguir al jugador que estás siguiendo.", noArgs(), (ctx, a) ->
			manager.brain().commandStopFollowing() ? "Dejaste de seguir." : "No estabas siguiendo a nadie."));
		r.register(tool("approach_player", "Caminar hasta un jugador y quedarte a su lado.", playerSchema("Nombre del jugador"), (ctx, a) -> {
			ServerPlayer p = ctx.resolvePlayer(Args.string(a, "player", true, 32));
			DanteEntity body = ctx.requireBody();
			if (p.level() != body.level()) throw new ToolException(p.getGameProfile().getName() + " está en otra dimensión; usá teleport_near_player");
			double dist = p.distanceTo(body);
			if (dist > 64) throw new ToolException(p.getGameProfile().getName() + " está a " + Math.round(dist) + " bloques, demasiado lejos para ir caminando; usá teleport_near_player");
			manager.brain().startTask(new Tasks.Approach(p));
			return "Vas caminando hacia " + p.getGameProfile().getName() + " (" + Math.round(dist) + " bloques).";
		}));
		r.register(tool("look_at_player", "Mirar fijamente a un jugador unos segundos.", playerSchema("Nombre del jugador"), (ctx, a) -> {
			ServerPlayer p = ctx.resolvePlayer(Args.string(a, "player", true, 32));
			DanteEntity body = ctx.requireBody();
			if (p.level() != body.level() || p.distanceTo(body) > 32) throw new ToolException("no ves a " + p.getGameProfile().getName() + " desde acá");
			manager.brain().attend(p, 100);
			return "Mirás a " + p.getGameProfile().getName() + ".";
		}));
		r.register(tool("wander", "Irte a pasear libremente por los alrededores.", noArgs(), (ctx, a) -> {
			ctx.requireBody();
			manager.brain().commandWander();
			return "Saliste a pasear.";
		}));
		r.register(tool("sit", "Sentarte y quedarte quieto en el lugar.", noArgs(), (ctx, a) -> {
			ctx.requireBody();
			manager.brain().commandSit(300);
			return "Te sentaste. Te quedás quieto hasta que te digan que te levantes (máximo 5 minutos).";
		}));
		r.register(tool("stand_up", "Levantarte si estabas sentado o durmiendo.", noArgs(), (ctx, a) -> {
			ctx.requireBody();
			return manager.brain().commandStand() ? "Te levantaste." : "Ya estabas parado.";
		}));
		r.register(tool("teleport_near_player", "Aparecer mágicamente cerca de un jugador que está lejos (o en otra dimensión).",
			playerSchema("Nombre del jugador"), 20 * 20, 1, (ctx, a) -> {
				ServerPlayer p = ctx.resolvePlayer(Args.string(a, "player", true, 32));
				DanteConfig.Behavior b = manager.config().behavior;
				if (!b.teleport_enabled) throw new ToolException("el teletransporte está desactivado en este servidor");
				DanteEntity body = manager.body();
				if (body != null && p.level() == body.level() && p.distanceTo(body) < 12) {
					throw new ToolException("ya estás cerca de " + p.getGameProfile().getName() + "; caminá");
				}
				if (body != null && p.level() != body.level() && !b.teleport_across_dimensions) {
					throw new ToolException("no podés cambiar de dimensión en este servidor");
				}
				if (!manager.brain().teleportOffCooldown()) throw new ToolException("todavía estás mareado del último teletransporte, esperá un poco");
				if (!manager.teleportNear(p)) throw new ToolException("no encontraste un lugar seguro cerca de " + p.getGameProfile().getName());
				return "Apareciste al lado de " + p.getGameProfile().getName() + ".";
			}));

		// ---------------------------------------------------------------- información
		r.register(tool("inspect_inventory", "Ver exactamente qué objetos tenés y de quién es cada uno.", noArgs(), (ctx, a) -> {
			DanteInventory inv = manager.inventory();
			if (inv.isEmpty()) return "Tu inventario está vacío (" + inv.capacity() + " espacios libres).";
			StringBuilder sb = new StringBuilder("Inventario (" + inv.slots().size() + "/" + inv.capacity() + "): ");
			for (OwnedStack s : inv.slots()) {
				sb.append(ItemMatcher.describe(s.stack())).append(" de ").append(s.owner() == null ? "nadie" : s.ownerName()).append("; ");
			}
			return sb.toString();
		}));
		r.register(tool("inspect_surroundings", "Mirar y olfatear alrededor: criaturas, objetos en el suelo (con id), bloques y cofres cercanos.",
			noArgs(), (ctx, a) -> {
				DanteEntity body = ctx.requireBody();
				String base = WorldSnapshot.capture(manager, body, ctx.speaker());
				List<BlockPos> containers = ContainerAccess.candidates((ServerLevel) body.level(), body.blockPosition(), manager.config().chests);
				if (!containers.isEmpty()) {
					base += "- Contenedores a tu alcance: " + containers.stream().limit(5).map(BlockPos::toShortString).toList() + "\n";
				}
				return base;
			}));

		// ---------------------------------------------------------------- objetos
		JsonObject giveProps = Args.props();
		giveProps.add("player", Args.stringProp("Nombre del jugador que recibe"));
		giveProps.add("item", Args.stringProp("Objeto: id de Minecraft (ej. minecraft:diamond) o 'all' para todo lo permitido"));
		giveProps.add("quantity", Args.intProp("Cantidad (0 u omitido = todos los que tengas de ese objeto)"));
		r.register(tool("give_item", "Entregarle a un jugador objetos de TU inventario. Si está lejos, caminás hasta él.",
			Args.schema(giveProps, "player", "item"), (ctx, a) -> giveItem(manager, ctx, a)));

		r.register(tool("return_items", "Devolverle a un jugador TODOS los objetos que te dio.", playerSchema("Nombre del jugador"), (ctx, a) -> {
			ServerPlayer p = ctx.resolvePlayer(Args.string(a, "player", true, 32));
			if (manager.inventory().count(s -> true, s -> s.ownedBy(p.getUUID())) == 0) {
				throw new ToolException("no tenés nada de " + p.getGameProfile().getName());
			}
			return deliver(manager, ctx, p, s -> true, s -> s.ownedBy(p.getUUID()), 0, "sus cosas");
		}));

		JsonObject takeProps = Args.props();
		takeProps.add("item", Args.stringProp("Objeto a guardar: id de Minecraft, nombre, o 'hand' para lo que tiene en la mano"));
		takeProps.add("quantity", Args.intProp("Cantidad (0 u omitido = todos)"));
		r.register(tool("take_item_from_player",
			"Guardar en tu mochila objetos del inventario del jugador que TE HABLA, solo si él te lo pidió (ej. 'guardame estos diamantes').",
			Args.schema(takeProps, "item"), (ctx, a) -> takeFromSpeaker(manager, ctx, a)));

		JsonObject collectProps = Args.props();
		collectProps.add("entity", Args.stringProp("Id del objeto en el suelo (de inspect_surroundings) o 'nearest' para el más cercano"));
		r.register(tool("collect_item", "Ir a recoger un objeto tirado en el suelo.", Args.schema(collectProps, "entity"), (ctx, a) -> collect(manager, ctx, a)));

		JsonObject storeProps = Args.props();
		storeProps.add("item", Args.stringProp("Objeto a guardar (id) o 'all'"));
		storeProps.add("quantity", Args.intProp("Cantidad (0 u omitido = todos)"));
		r.register(tool("store_in_chest", "Llevar objetos de tu mochila al cofre accesible más cercano (en nombre de quien te habla).",
			Args.schema(storeProps, "item"), (ctx, a) -> containerTask(manager, ctx, a, Tasks.Container.Mode.STORE)));
		r.register(tool("take_from_chest", "Sacar objetos del cofre accesible más cercano y guardarlos en tu mochila (para quien te habla).",
			Args.schema(storeProps, "item"), (ctx, a) -> containerTask(manager, ctx, a, Tasks.Container.Mode.TAKE)));

		// ---------------------------------------------------------------- expresión
		JsonObject msgProps = Args.props();
		msgProps.add("message", Args.stringProp("Lo que decís en el chat"));
		r.register(tool("send_message", "Decir algo en el chat ahora mismo (por ejemplo antes de hacer una acción larga).",
			Args.schema(msgProps, "message"), 0, 2, (ctx, a) -> {
				String msg = Args.string(a, "message", true, manager.config().chat.max_message_length);
				msg = manager.conversation().guardOutput(msg);
				manager.speaker().say(msg, ctx.speaker(), false, true);
				ctx.markSpoke(msg);
				return "Mensaje enviado.";
			}));

		JsonObject emoteProps = Args.props();
		emoteProps.add("action", Args.enumProp("Gesto", List.of("bark", "wag_tail", "tilt_head", "jump", "spin", "shake", "howl", "sleep", "play_dead")));
		r.register(tool("emote", "Hacer un gesto de perro.", Args.schema(emoteProps, "action"), 40, 2, (ctx, a) -> emote(manager, ctx, a)));

		// ---------------------------------------------------------------- memoria
		JsonObject rememberProps = Args.props();
		rememberProps.add("note", Args.stringProp("Qué recordar, en una frase"));
		rememberProps.add("kind", Args.enumProp("Tipo", List.of("fact", "preference", "commitment", "event")));
		rememberProps.add("about", Args.enumProp("Sobre quién: 'speaker' (quien te habla) o 'world' (general)", List.of("speaker", "world")));
		rememberProps.add("importance", Args.intProp("Importancia 1-5"));
		r.register(tool("remember", "Guardar en tu memoria algo importante: un dato, una preferencia, una promesa pendiente o un evento.",
			Args.schema(rememberProps, "note", "kind"), 0, 3, (ctx, a) -> {
				String note = Args.string(a, "note", true, 300);
				String kind = Args.string(a, "kind", true, 20);
				if (!List.of("fact", "preference", "commitment", "event").contains(kind)) throw new ToolException("tipo inválido");
				boolean world = Args.string(a, "about", false, 10).equals("world") || ctx.speaker() == null;
				int importance = Args.integer(a, "importance", kind.equals("commitment") ? 4 : 2, 1, 5);
				UUID who = world ? MemoryStore.GLOBAL : ctx.speaker().getUUID();
				manager.memory().remember(who, kind, note, importance);
				return "Lo vas a recordar.";
			}));
		JsonObject completeProps = Args.props();
		completeProps.add("id", Args.intProp("Número de la promesa (#id en tu memoria)"));
		r.register(tool("complete_commitment", "Marcar como cumplida una promesa o tarea pendiente de tu memoria.",
			Args.schema(completeProps, "id"), (ctx, a) -> {
				int id = Args.integer(a, "id", -1, -1, Integer.MAX_VALUE);
				if (id < 0) throw new ToolException("id inválido");
				manager.memory().resolve(id);
				return "Marcada como cumplida.";
			}));
		return r;
	}

	// ======================================================================== implementación

	private static Predicate<OwnedStack> allowedFor(DanteManager manager, ServerPlayer receiver, ToolContext ctx) {
		boolean share = manager.config().inventory.share_items && !ctx.ownItemsOnly();
		return s -> share || s.owner() == null || s.ownedBy(receiver.getUUID());
	}

	private static String giveItem(DanteManager manager, ToolContext ctx, JsonObject a) throws ToolException {
		if (!manager.config().inventory.enabled) throw new ToolException("el inventario está desactivado");
		ServerPlayer p = ctx.resolvePlayer(Args.string(a, "player", true, 32));
		String itemArg = Args.string(a, "item", true, 64);
		int qty = Args.integer(a, "quantity", 0, 0, 64 * 54);
		Predicate<ItemStack> parsed = ItemMatcher.parse(itemArg);
		Predicate<ItemStack> matcher = parsed == null ? s -> true : parsed;
		DanteInventory inv = manager.inventory();
		int have = inv.count(matcher, s -> true);
		if (have == 0) throw new ToolException("no tenés '" + itemArg + "' en tu inventario");
		Predicate<OwnedStack> allowed = allowedFor(manager, p, ctx);
		int allowedCount = inv.count(matcher, allowed);
		if (allowedCount == 0) {
			List<String> owners = new ArrayList<>();
			for (OwnedStack s : inv.slots()) if (matcher.test(s.stack()) && s.owner() != null) owners.add(s.ownerName());
			throw new ToolException("esos objetos no son de " + p.getGameProfile().getName() + " (son de " + String.join(", ", owners.stream().distinct().toList()) + ")");
		}
		if (qty > allowedCount) {
			qty = allowedCount;
		}
		return deliver(manager, ctx, p, matcher, allowed, qty, itemArg);
	}

	/** Entrega inmediata si está al alcance; si no, encargo de caminar y entregar al llegar. */
	private static String deliver(DanteManager manager, ToolContext ctx, ServerPlayer p, Predicate<ItemStack> matcher,
			Predicate<OwnedStack> allowed, int qty, String label) throws ToolException {
		DanteEntity body = ctx.requireBody();
		DanteConfig.Inventory cfg = manager.config().inventory;
		if (p.level() != body.level()) throw new ToolException(p.getGameProfile().getName() + " está en otra dimensión; primero teletransportate");
		double dist = p.distanceTo(body);
		if (dist <= cfg.give_reach) {
			List<OwnedStack> stacks = manager.inventory().extract(matcher, allowed, qty, p.getUUID());
			InventoryActions.Transfer t = InventoryActions.giveToPlayer(p, stacks);
			manager.onItemsGiven(p, t);
			return "Le diste a " + p.getGameProfile().getName() + ": " + t.describe() + (t.dropped() > 0 ? " (" + t.dropped() + " quedaron en el suelo porque no tenía lugar)" : "") + ".";
		}
		if (dist > cfg.delivery_max_distance) {
			throw new ToolException(p.getGameProfile().getName() + " está a " + Math.round(dist) + " bloques; acercate o teletransportate primero");
		}
		manager.brain().startTask(new Tasks.Deliver(p, matcher, allowed, qty, label));
		return "Vas caminando hacia " + p.getGameProfile().getName() + " (" + Math.round(dist) + " bloques) para entregarle " + label
			+ ". La entrega ocurre al llegar.";
	}

	private static String takeFromSpeaker(DanteManager manager, ToolContext ctx, JsonObject a) throws ToolException {
		if (!manager.config().inventory.enabled) throw new ToolException("el inventario está desactivado");
		ServerPlayer p = ctx.requireSpeaker();
		DanteEntity body = ctx.requireBody();
		if (p.level() != body.level() || p.distanceTo(body) > manager.config().inventory.give_reach + 2) {
			throw new ToolException(p.getGameProfile().getName() + " tiene que estar al lado tuyo para darte cosas");
		}
		String itemArg = Args.string(a, "item", true, 64);
		int qty = Args.integer(a, "quantity", 0, 0, 64 * 54);
		Predicate<ItemStack> matcher;
		if (itemArg.equalsIgnoreCase("hand") || itemArg.equalsIgnoreCase("mano")) {
			ItemStack held = p.getMainHandItem();
			if (held.isEmpty()) throw new ToolException(p.getGameProfile().getName() + " no tiene nada en la mano");
			ItemStack sample = held.copy();
			matcher = s -> ItemStack.isSameItemSameComponents(s, sample);
		} else {
			matcher = ItemMatcher.parse(itemArg);
			if (matcher == null) throw new ToolException("decí qué objeto guardar (no podés llevarte todo el inventario de alguien)");
		}
		if (manager.inventory().freeSlots() == 0 && manager.inventory().count(matcher, s -> s.ownedBy(p.getUUID())) == 0) {
			throw new ToolException("tu mochila está llena");
		}
		InventoryActions.Transfer t = InventoryActions.takeFromPlayer(p, matcher, qty, manager.inventory());
		if (t.total() == 0) throw new ToolException(p.getGameProfile().getName() + " no tiene '" + itemArg + "' (o no te entra)");
		manager.onItemsReceived(p, t);
		return "Guardaste en tu mochila: " + t.describe() + " (de " + p.getGameProfile().getName() + ").";
	}

	private static String collect(DanteManager manager, ToolContext ctx, JsonObject a) throws ToolException {
		if (!manager.config().inventory.enabled) throw new ToolException("el inventario está desactivado");
		DanteEntity body = ctx.requireBody();
		ServerLevel level = (ServerLevel) body.level();
		String ref = Args.string(a, "entity", true, 32).replace("id", "").strip();
		ItemEntity target = null;
		if (ref.equalsIgnoreCase("nearest") || ref.isEmpty()) {
			target = level.getEntitiesOfClass(ItemEntity.class, body.getBoundingBox().inflate(16, 4, 16), e -> !e.hasPickUpDelay())
				.stream().min(Comparator.comparingDouble(e -> e.distanceToSqr(body))).orElse(null);
		} else {
			try {
				Entity e = level.getEntity(Integer.parseInt(ref.replaceAll("[^0-9]", "")));
				if (e instanceof ItemEntity item) target = item;
			} catch (NumberFormatException ignored) {
			}
		}
		if (target == null || target.isRemoved()) throw new ToolException("no hay ese objeto en el suelo cerca tuyo");
		if (target.distanceTo(body) > 24) throw new ToolException("ese objeto está demasiado lejos");
		if (manager.inventory().roomFor(target.getItem(), ctx.speaker() == null ? null : ctx.speaker().getUUID()) <= 0
			&& manager.inventory().freeSlots() == 0) {
			throw new ToolException("tu mochila está llena");
		}
		manager.brain().startTask(new Tasks.Collect(target, ctx.speaker()));
		return "Vas a buscar " + ItemMatcher.describe(target.getItem()) + " (a " + Math.round(target.distanceTo(body)) + " bloques).";
	}

	private static String containerTask(DanteManager manager, ToolContext ctx, JsonObject a, Tasks.Container.Mode mode) throws ToolException {
		DanteConfig.Chests cfg = manager.config().chests;
		if (!cfg.enabled || !manager.config().inventory.enabled) throw new ToolException("el uso de cofres está desactivado");
		ServerPlayer p = ctx.requireSpeaker();
		DanteEntity body = ctx.requireBody();
		ServerLevel level = (ServerLevel) body.level();
		String itemArg = Args.string(a, "item", true, 64);
		int qty = Args.integer(a, "quantity", 0, 0, 64 * 54);
		Predicate<ItemStack> parsed = ItemMatcher.parse(itemArg);
		Predicate<ItemStack> matcher = parsed == null ? s -> true : parsed;
		Predicate<OwnedStack> allowed = s -> s.owner() == null || s.ownedBy(p.getUUID());
		if (mode == Tasks.Container.Mode.STORE && manager.inventory().count(matcher, allowed) == 0) {
			throw new ToolException("no tenés '" + itemArg + "' de " + p.getGameProfile().getName() + " para guardar");
		}
		if (mode == Tasks.Container.Mode.TAKE && parsed == null) {
			throw new ToolException("decí qué objeto sacar del cofre");
		}
		String lastDenied = "no hay cofres permitidos cerca";
		for (BlockPos pos : ContainerAccess.candidates(level, body.blockPosition(), cfg)) {
			Object check = ContainerAccess.check(level, pos, p, cfg);
			if (check instanceof ContainerAccess.Denied d) {
				lastDenied = d.reason();
				continue;
			}
			ContainerAccess.Found found = (ContainerAccess.Found) check;
			if (mode == Tasks.Container.Mode.TAKE && !containerHas(found, matcher)) {
				lastDenied = "el cofre más cercano no tiene '" + itemArg + "'";
				continue;
			}
			manager.brain().startTask(new Tasks.Container(pos, mode, p, matcher, allowed, qty));
			return "Vas al cofre en " + pos.toShortString() + " para " + (mode == Tasks.Container.Mode.STORE ? "guardar " : "sacar ") + itemArg + ".";
		}
		throw new ToolException(lastDenied);
	}

	private static boolean containerHas(ContainerAccess.Found found, Predicate<ItemStack> matcher) {
		for (int i = 0; i < found.container().getContainerSize(); i++) {
			ItemStack s = found.container().getItem(i);
			if (!s.isEmpty() && matcher.test(s)) return true;
		}
		return false;
	}

	private static String emote(DanteManager manager, ToolContext ctx, JsonObject a) throws ToolException {
		DanteEntity body = ctx.requireBody();
		ServerLevel level = (ServerLevel) body.level();
		String action = Args.string(a, "action", true, 20);
		switch (action) {
			case "bark" -> body.bark();
			case "wag_tail" -> {
				body.setMood(100);
				level.sendParticles(ParticleTypes.HEART, body.getX(), body.getY() + 1.2, body.getZ(), 2, 0.3, 0.1, 0.3, 0.0);
			}
			case "tilt_head" -> body.startListening(60);
			case "jump" -> body.getJumpControl().jump();
			case "spin" -> manager.brain().forceSpin();
			case "shake" -> body.shake();
			case "howl" -> {
				body.whine();
				body.setDantePose(DanteEntity.POSE_SITTING);
			}
			case "sleep" -> {
				manager.brain().commandSit(60);
				body.setDantePose(DanteEntity.POSE_SLEEPING);
			}
			case "play_dead" -> {
				manager.brain().commandSit(10);
				body.setDantePose(DanteEntity.POSE_SLEEPING);
			}
			default -> throw new ToolException("gesto desconocido: " + action);
		}
		return "Hiciste el gesto '" + action + "'.";
	}

	static String itemId(ItemStack s) {
		return BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
	}
}
