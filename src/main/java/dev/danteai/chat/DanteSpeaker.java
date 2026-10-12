package dev.danteai.chat;

import dev.danteai.config.DanteConfig;
import dev.danteai.entity.DanteEntity;
import dev.danteai.world.DanteManager;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/** Publica lo que dice Dante en el chat (identificado con su nombre) y, si está disponible, en voz. */
public final class DanteSpeaker {
	private static final java.util.UUID NO_FOCUS = new java.util.UUID(0L, 0L);
	private final DanteManager manager;
	private final RecentMessages recent = new RecentMessages(10_000);

	public DanteSpeaker(DanteManager manager) {
		this.manager = manager;
	}

	private static final java.util.Set<String> COLORS = java.util.Set.of("BLACK", "DARK_BLUE", "DARK_GREEN", "DARK_AQUA", "DARK_RED",
		"DARK_PURPLE", "GOLD", "GRAY", "DARK_GRAY", "BLUE", "GREEN", "AQUA", "RED", "LIGHT_PURPLE", "YELLOW", "WHITE");

	private ChatFormatting nameColor() {
		String name = manager.config().dante.name_color.strip().toUpperCase(java.util.Locale.ROOT);
		return COLORS.contains(name) ? ChatFormatting.valueOf(name) : ChatFormatting.GOLD;
	}

	/**
	 * Mensaje hablado de Dante.
	 *
	 * @param focus       jugador al que le responde (siempre lo recibe); puede ser {@code null}
	 * @param spontaneous si es un comentario espontáneo (solo lo reciben jugadores cercanos)
	 * @param withVoice   leerlo también en voz alta por Simple Voice Chat
	 */
	public void say(String text, ServerPlayer focus, boolean spontaneous, boolean withVoice) {
		DanteConfig cfg = manager.config();
		String clean = TextUtil.sanitizeForChat(text, cfg.dante.name);
		if (clean.isEmpty()) return;
		java.util.UUID key = focus == null ? NO_FOCUS : focus.getUUID();
		if (recent.isDuplicate(key, clean, System.currentTimeMillis())) return;
		List<String> parts = TextUtil.split(clean, cfg.chat.max_message_length, 4);
		boolean nearbyOnly = spontaneous || cfg.chat.reply_audience.equals("nearby");
		List<ServerPlayer> audience = audience(focus, nearbyOnly);
		for (String part : parts) {
			MutableComponent line = Component.literal("<").append(Component.literal(cfg.dante.name).withStyle(nameColor()))
				.append(Component.literal("> " + part));
			for (ServerPlayer p : audience) p.sendSystemMessage(line);
		}
		manager.server().sendSystemMessage(Component.literal("<" + cfg.dante.name + "> " + clean));
		DanteEntity body = manager.body();
		if (body != null) body.startSpeaking(Math.min(200, 20 + clean.length() * 2));
		if (withVoice) manager.voice().speak(clean);
	}

	/** Acción narrada en tercera persona: "* Dante mueve la cola". */
	public void emote(String action, ServerPlayer focus) {
		DanteConfig cfg = manager.config();
		MutableComponent line = Component.literal("* ").append(Component.literal(cfg.dante.name).withStyle(nameColor()))
			.append(Component.literal(" " + action)).withStyle(ChatFormatting.ITALIC, ChatFormatting.GRAY);
		for (ServerPlayer p : audience(focus, true)) p.sendSystemMessage(line);
	}

	/** Lo que un jugador le dijo a Dante por voz, para que los demás tengan contexto. */
	public void echoVoiceInput(ServerPlayer speaker, String text) {
		MutableComponent line = Component.literal("🎤 " + speaker.getGameProfile().getName() + ": " + text)
			.withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC);
		for (ServerPlayer p : audience(speaker, true)) p.sendSystemMessage(line);
	}

	public void actionBar(ServerPlayer player, String text) {
		player.displayClientMessage(Component.literal(text).withStyle(ChatFormatting.GRAY), true);
	}

	private List<ServerPlayer> audience(ServerPlayer focus, boolean nearbyOnly) {
		List<ServerPlayer> out = new ArrayList<>();
		DanteEntity body = manager.body();
		double radius = manager.config().chat.nearby_radius;
		for (ServerPlayer p : manager.server().getPlayerList().getPlayers()) {
			if (p == focus) continue;
			if (!nearbyOnly) {
				out.add(p);
			} else if (body != null && p.level() == body.level() && p.distanceTo(body) <= radius) {
				out.add(p);
			}
		}
		if (focus != null) out.add(focus);
		return out;
	}
}
