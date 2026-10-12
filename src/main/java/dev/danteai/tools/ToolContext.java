package dev.danteai.tools;

import dev.danteai.entity.DanteEntity;
import dev.danteai.world.DanteManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Contexto de un turno de conversación: quién habla y cuántas veces se usó cada herramienta. */
public final class ToolContext {
	private final DanteManager manager;
	private final ServerPlayer speaker;
	private final Map<String, Integer> usesThisTurn = new HashMap<>();
	private boolean spokeViaTool;
	private boolean ownItemsOnly;
	private final java.util.List<String> spokenViaTool = new java.util.ArrayList<>();

	public ToolContext(DanteManager manager, ServerPlayer speaker) {
		this.manager = manager;
		this.speaker = speaker;
	}

	public DanteManager manager() {
		return manager;
	}

	/** Jugador que inició la conversación (puede ser {@code null} en comentarios espontáneos). */
	public ServerPlayer speaker() {
		return speaker;
	}

	public ServerPlayer requireSpeaker() throws ToolException {
		if (speaker == null || speaker.isRemoved()) throw new ToolException("no hay un jugador hablando con vos");
		return speaker;
	}

	public DanteEntity requireBody() throws ToolException {
		DanteEntity body = manager.body();
		if (body == null || body.isRemoved()) throw new ToolException("tu cuerpo está en una zona sin cargar; primero tenés que teletransportarte cerca de alguien");
		return body;
	}

	public ServerLevel level() throws ToolException {
		return (ServerLevel) requireBody().level();
	}

	/** Resuelve un jugador conectado por nombre (sin distinguir mayúsculas). "yo"/"me"/vacío = quien habla. */
	public ServerPlayer resolvePlayer(String name) throws ToolException {
		String n = name == null ? "" : name.strip().replace("@", "");
		String lower = n.toLowerCase(Locale.ROOT);
		if (n.isEmpty() || lower.equals("me") || lower.equals("yo") || lower.equals("mí") || lower.equals("mi") || lower.equals("speaker")) {
			return requireSpeaker();
		}
		for (ServerPlayer p : manager.server().getPlayerList().getPlayers()) {
			if (p.getGameProfile().getName().equalsIgnoreCase(n)) {
				if (p.isSpectator()) throw new ToolException(n + " está en modo espectador");
				return p;
			}
		}
		throw new ToolException("no hay ningún jugador conectado llamado '" + n + "'");
	}

	int countUse(String tool) {
		return usesThisTurn.merge(tool, 1, Integer::sum);
	}

	public void markSpoke() {
		spokeViaTool = true;
	}

	/** Registra lo dicho con send_message para no repetirlo como respuesta final. */
	public void markSpoke(String text) {
		spokeViaTool = true;
		spokenViaTool.add(text);
	}

	public boolean alreadySpoke(String text) {
		for (String s : spokenViaTool) if (dev.danteai.chat.TextUtil.sameMessage(s, text)) return true;
		return false;
	}

	/** Si es true, las herramientas solo entregan objetos propios del receptor o sin dueño. */
	public void setOwnItemsOnly(boolean value) {
		this.ownItemsOnly = value;
	}

	public boolean ownItemsOnly() {
		return ownItemsOnly;
	}

	public boolean spokeViaTool() {
		return spokeViaTool;
	}
}
