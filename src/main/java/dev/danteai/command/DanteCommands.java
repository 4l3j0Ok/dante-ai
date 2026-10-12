package dev.danteai.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.danteai.DanteAi;
import dev.danteai.behavior.Activity;
import dev.danteai.entity.DanteEntity;
import dev.danteai.inventory.InventoryActions;
import dev.danteai.inventory.ItemMatcher;
import dev.danteai.inventory.OwnedStack;
import dev.danteai.world.DanteManager;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Comandos {@code /dante}. "status" es público; el resto requiere el nivel de permisos configurado. */
public final class DanteCommands {
	private static final com.mojang.brigadier.exceptions.SimpleCommandExceptionType NOT_ACTIVE =
		new com.mojang.brigadier.exceptions.SimpleCommandExceptionType(Component.literal("Dante AI no está activo"));

	private DanteCommands() {
	}

	private static int adminLevel() {
		return switch (DanteAi.config().dante.admin_permission_level) {
			case 0 -> Commands.LEVEL_ALL;
			case 1 -> Commands.LEVEL_MODERATORS;
			case 3 -> Commands.LEVEL_ADMINS;
			case 4 -> Commands.LEVEL_OWNERS;
			default -> Commands.LEVEL_GAMEMASTERS;
		};
	}

	private static java.util.function.Predicate<CommandSourceStack> adminCheck() {
		return source -> source.hasPermission(adminLevel());
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		java.util.function.Predicate<CommandSourceStack> admin = adminCheck();
		var voiceTest = Commands.literal("test")
			.executes(ctx -> voiceTest(ctx, "¡Guau! Hola, soy Dante. ¿Me escuchan bien?"))
			.then(Commands.argument("text", StringArgumentType.greedyString())
				.executes(ctx -> voiceTest(ctx, StringArgumentType.getString(ctx, "text"))));
		var voiceAsk = Commands.literal("ask")
			.then(Commands.argument("question", StringArgumentType.greedyString())
				.executes(DanteCommands::voiceAsk));
		var voice = Commands.literal("voice").requires(admin).then(voiceTest).then(voiceAsk);
		dispatcher.register(Commands.literal("dante")
			.executes(DanteCommands::status)
			.then(Commands.literal("status").executes(DanteCommands::status))
			.then(Commands.literal("retry").executes(DanteCommands::retry))
			.then(Commands.literal("summon").requires(admin).executes(DanteCommands::summon))
			.then(Commands.literal("remove").requires(admin).executes(DanteCommands::remove))
			.then(Commands.literal("reload").requires(admin).executes(DanteCommands::reload))
			.then(Commands.literal("reset").requires(admin)
				.then(Commands.literal("behavior").executes(ctx -> resetBehavior(ctx)))
				.then(Commands.literal("memory").executes(ctx -> resetMemory(ctx))))
			.then(Commands.literal("forget").requires(admin)
				.then(Commands.argument("player", EntityArgument.player()).executes(DanteCommands::forget)))
			.then(Commands.literal("inventory").requires(admin)
				.executes(DanteCommands::inventoryList)
				.then(Commands.literal("list").executes(DanteCommands::inventoryList))
				.then(Commands.literal("dump").executes(DanteCommands::inventoryDump))
				.then(Commands.literal("return").then(Commands.argument("player", EntityArgument.player()).executes(DanteCommands::inventoryReturn))))
			.then(Commands.literal("say").requires(admin)
				.then(Commands.argument("message", StringArgumentType.greedyString()).executes(DanteCommands::say)))
			.then(Commands.literal("behavior").requires(admin)
				.then(Commands.argument("activity", StringArgumentType.word())
					.suggests((c, b) -> {
						for (Activity a : Activity.values()) b.suggest(a.name().toLowerCase(Locale.ROOT));
						return b.buildFuture();
					})
					.executes(DanteCommands::behavior)))
			.then(Commands.literal("llm").requires(admin)
				.then(Commands.literal("test").executes(DanteCommands::llmTest)))
			.then(voice));
	}

	private static DanteManager manager(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		DanteManager m = DanteAi.manager();
		if (m == null) throw NOT_ACTIVE.create();
		return m;
	}

	private static void reply(CommandContext<CommandSourceStack> ctx, String text) {
		ctx.getSource().sendSuccess(() -> Component.literal(text), false);
	}

	private static int status(CommandContext<CommandSourceStack> ctx) {
		DanteManager m = DanteAi.manager();
		if (m == null) {
			reply(ctx, "Dante AI no está activo en este servidor.");
			return 0;
		}
		String name = m.config().dante.name;
		ctx.getSource().sendSuccess(() -> Component.literal("— " + name + " —").withStyle(ChatFormatting.GOLD), false);
		boolean admin = ctx.getSource().hasPermission(adminLevel());
		if (admin) reply(ctx, "Ubicación: " + m.statusLine());
		reply(ctx, "Actividad: " + m.brain().describeActivity());
		reply(ctx, String.format(Locale.ROOT, "Energía %.0f%% · Social %.0f%% · Curiosidad %.0f%%",
			m.brain().needs().energy() * 100, m.brain().needs().social() * 100, m.brain().needs().curiosity() * 100));
		reply(ctx, "Inventario: " + m.inventory().slots().size() + "/" + m.inventory().capacity() + " espacios");
		if (!admin) return 1;
		reply(ctx, "LLM: " + (m.llm().enabled() ? m.llm().describe() + " (en curso " + m.llm().active() + ", en cola " + m.llm().queued() + ")"
			: "desactivado (modo sin IA)"));
		reply(ctx, "Memoria: " + (m.memory().persistent() ? "SQLite" : "temporal en RAM"));
		reply(ctx, "Voz: " + m.voice().status());
		reply(ctx, "Servicios: " + m.servicesStatus());
		return 1;
	}

	/** Cualquier jugador: vuelve a responder su último mensaje, cancelando una respuesta que quedó colgada. */
	private static int retry(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		DanteManager m = manager(ctx);
		ServerPlayer player = ctx.getSource().getPlayer();
		if (!m.conversation().retry(player)) {
			reply(ctx, m.config().dante.name + " todavía no tiene nada pendiente de responderte.");
			return 0;
		}
		reply(ctx, "Lo vuelvo a intentar, mi rey.");
		return 1;
	}

	private static int summon(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		DanteManager m = manager(ctx);
		if (!m.servicesOk()) {
			reply(ctx, "No puedo invocar a " + m.config().dante.name + ": " + m.servicesStatus()
				+ ". Aparecerá solo cuando todos respondan (dante.require_services).");
			return 0;
		}
		ServerPlayer player = ctx.getSource().getPlayer();
		DanteEntity body;
		if (player != null) {
			m.data().setRemovedByAdmin(false);
			if (m.body() != null && m.teleportNear(player)) {
				reply(ctx, m.config().dante.name + " vino corriendo.");
				return 1;
			}
			body = m.spawnNear(player.serverLevel(), player.blockPosition());
		} else {
			body = m.spawnNear(ctx.getSource().getLevel(), net.minecraft.core.BlockPos.containing(ctx.getSource().getPosition()));
		}
		reply(ctx, body != null ? m.config().dante.name + " apareció." : "No se pudo crear a " + m.config().dante.name + ".");
		return body != null ? 1 : 0;
	}

	private static int remove(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		DanteManager m = manager(ctx);
		m.remove();
		reply(ctx, m.config().dante.name + " fue retirado del mundo (su inventario y memoria se conservan). Usá /dante summon para traerlo.");
		return 1;
	}

	private static int reload(CommandContext<CommandSourceStack> ctx) {
		String error = DanteAi.reload();
		reply(ctx, error == null ? "Configuración recargada." : "Error al recargar (se mantiene la anterior): " + error);
		return error == null ? 1 : 0;
	}

	private static int resetBehavior(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		manager(ctx).resetBrain();
		reply(ctx, "Comportamiento reiniciado.");
		return 1;
	}

	private static int resetMemory(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		manager(ctx).memory().clearAll();
		reply(ctx, "Memoria borrada.");
		return 1;
	}

	private static int forget(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer p = EntityArgument.getPlayer(ctx, "player");
		manager(ctx).memory().forgetPlayer(p.getUUID());
		reply(ctx, "Dante olvidó todo sobre " + p.getGameProfile().getName() + ".");
		return 1;
	}

	private static int inventoryList(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		DanteManager m = manager(ctx);
		List<OwnedStack> slots = m.inventory().slots();
		reply(ctx, "Inventario de " + m.config().dante.name + " (" + slots.size() + "/" + m.inventory().capacity() + "):");
		for (OwnedStack s : slots) {
			reply(ctx, " - " + ItemMatcher.describe(s.stack()) + " · dueño: " + (s.owner() == null ? "nadie" : s.ownerName()));
		}
		return slots.size();
	}

	/** Entrega todo el inventario de Dante al administrador (nunca se borran objetos). */
	private static int inventoryDump(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		DanteManager m = manager(ctx);
		ServerPlayer admin = ctx.getSource().getPlayerOrException();
		InventoryActions.Transfer t = InventoryActions.giveToPlayer(admin, m.inventory().extractAll());
		reply(ctx, "Recibiste: " + t.describe());
		return t.total();
	}

	private static int inventoryReturn(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		DanteManager m = manager(ctx);
		ServerPlayer p = EntityArgument.getPlayer(ctx, "player");
		InventoryActions.Transfer t = InventoryActions.giveToPlayer(p, m.inventory().extract(s -> true, s -> s.ownedBy(p.getUUID()), 0, p.getUUID()));
		reply(ctx, "Se le devolvió a " + p.getGameProfile().getName() + ": " + t.describe());
		return t.total();
	}

	private static int say(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		manager(ctx).speaker().say(StringArgumentType.getString(ctx, "message"), null, false, true);
		return 1;
	}

	private static int behavior(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		String value = StringArgumentType.getString(ctx, "activity").toUpperCase(Locale.ROOT);
		Activity activity = Arrays.stream(Activity.values()).filter(a -> a.name().equals(value)).findFirst().orElse(null);
		if (activity == null || activity == Activity.INTERACTING) {
			reply(ctx, "Actividad inválida.");
			return 0;
		}
		manager(ctx).brain().forceActivity(activity);
		reply(ctx, "Actividad forzada: " + activity.displayName());
		return 1;
	}

	private static int llmTest(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		DanteManager m = manager(ctx);
		if (!m.llm().enabled()) {
			reply(ctx, "El LLM está desactivado o le falta la API key.");
			return 0;
		}
		reply(ctx, "Probando " + m.llm().describe() + "…");
		CommandSourceStack source = ctx.getSource();
		long start = System.currentTimeMillis();
		m.llm().simple(m.personality(), "Saludá en una frase corta.", 80).whenCompleteAsync((text, error) -> {
			long ms = System.currentTimeMillis() - start;
			if (error != null) {
				source.sendFailure(Component.literal("Falló (" + ms + " ms): " + dev.danteai.llm.HttpSupport.unwrap(error).getMessage()));
			} else {
				source.sendSuccess(() -> Component.literal("OK (" + ms + " ms): " + text), false);
			}
		}, m.server());
		return 1;
	}

	private static int voiceTest(CommandContext<CommandSourceStack> ctx, String text) throws CommandSyntaxException {
		DanteManager m = manager(ctx);
		reply(ctx, "Voz: " + m.voice().status());
		CommandSourceStack source = ctx.getSource();
		m.voice().speak(text).thenAcceptAsync(ok -> source.sendSuccess(() -> Component.literal(ok
			? "Reproduciendo la voz de Dante."
			: "No se pudo reproducir (revisá el log del servidor)."), false), m.server());
		return 1;
	}

	private static int voiceAsk(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		DanteManager m = manager(ctx);
		String question = StringArgumentType.getString(ctx, "question").strip();
		if (question.isEmpty()) return 0;
		m.conversation().onChat(player, DanteAi.config().dante.name + ", " + question);
		reply(ctx, "Pregunta enviada a Dante; responderá por chat y, si Simple Voice Chat está conectado, con audio.");
		return 1;
	}
}
