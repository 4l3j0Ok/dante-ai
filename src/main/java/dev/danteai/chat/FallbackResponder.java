package dev.danteai.chat;

import com.google.gson.JsonObject;
import dev.danteai.entity.DanteEntity;
import dev.danteai.llm.LlmMessage;
import dev.danteai.llm.ToolCall;
import dev.danteai.tools.ToolContext;
import dev.danteai.world.DanteManager;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Random;

/**
 * Respuestas sin LLM. Interpreta órdenes simples por palabras clave y las ejecuta con las mismas herramientas
 * validadas que usa el modelo, así el comportamiento es coherente aunque el servidor de IA esté caído.
 */
public final class FallbackResponder {
	private static final Random RANDOM = new Random();

	private FallbackResponder() {
	}

	public static void respond(DanteManager manager, ServerPlayer player, String message) {
		respond(manager, player, FallbackIntentParser.parse(message));
	}

	/** Respuesta con textos de respaldo (sin LLM, o porque el LLM acaba de fallar). */
	public static void respond(DanteManager manager, ServerPlayer player, FallbackIntentParser.Intent intent) {
		respond(manager, player, intent, false);
	}

	/**
	 * Ejecuta la orden al instante. Con {@code viaLlm} lo que dice Dante lo redacta el LLM (los textos fijos solo se usan
	 * si falla); sin él, se usan los textos fijos.
	 */
	public static void respond(DanteManager manager, ServerPlayer player, FallbackIntentParser.Intent intent, boolean viaLlm) {
		String name = player.getGameProfile().getName();
		ToolContext ctx = new ToolContext(manager, player);
		// Sin el criterio del LLM no hay persuasión posible: solo se entregan objetos propios o sin dueño.
		ctx.setOwnItemsOnly(true);
		DanteEntity body = manager.body();
		switch (intent.type()) {
			case SILENCE -> {
				manager.voice().silence();
				manager.speaker().emote("se queda en silencio", player);
			}
			case FOLLOW -> run(manager, ctx, viaLlm, "ya empezaste a seguirlo", "follow_player", args("player", name), "Dale, mi rey, te sigo. Vos poné el rumbo, yo pongo la facha.", "Bancá, mi rey, no puedo seguirte ahora.");
			case STOP_FOLLOW -> run(manager, ctx, viaLlm, "dejaste de seguirlo", "stop_following", new JsonObject(), "*se queda quieto*", "*mueve la cola*");
			case SIT -> run(manager, ctx, viaLlm, "te sentaste y te quedás quieto", "sit", new JsonObject(), "*se sienta y te mira*", "*se hace el distraído*");
			case STAND -> run(manager, ctx, viaLlm, "te levantaste", "stand_up", new JsonObject(), "*se levanta de un salto*", "*bosteza*");
			case COME -> run(manager, ctx, viaLlm, "ya vas caminando hacia él", "approach_player", args("player", name), "Ahí voy, mi rey. Servicio de perro a domicilio.", "Mi rey, no puedo acercarme ahora.");
			case WANDER -> run(manager, ctx, viaLlm, "salís a pasear por los alrededores", "wander", new JsonObject(), "*sale corriendo a olfatear todo*", "*se queda echado*");
			case INVENTORY -> {
				LlmMessage.ToolResult r = call(manager, ctx, "inspect_inventory", new JsonObject());
				manager.speaker().say("Mi rey, este es el inventario de la mochila, no un bolso sin fondo: " + r.content(), player, false, false);
			}
			case RETURN_ALL -> run(manager, ctx, viaLlm, "le devolviste todas las cosas que te había dado", "return_items", args("player", name), "*te devuelve tus cosas*", "*ladea la cabeza*");
			case GIVE_ITEM -> {
				JsonObject a = args("player", name);
				a.addProperty("item", intent.itemHint().isBlank() ? "all" : intent.itemHint());
				run(manager, ctx, viaLlm, "le diste lo que te pidió", "give_item", a, "*te trae lo que pediste*", "*ladea la cabeza*");
			}
			case TAKE_HELD -> {
				JsonObject a = new JsonObject();
				a.addProperty("item", intent.itemHint().isBlank() ? "hand" : intent.itemHint());
				run(manager, ctx, viaLlm, "guardaste en tu mochila lo que te dio", "take_item_from_player", a, "*lo guarda en su mochila*", "*olfatea tu mano*");
			}
			case PICKUP -> run(manager, ctx, viaLlm, "salís corriendo a buscar el objeto", "collect_item", args("entity", "nearest"), "*sale corriendo a buscarlo*", "*no ve nada*");
			case STORE_CHEST -> {
				JsonObject a = new JsonObject();
				a.addProperty("item", intent.itemHint().isBlank() ? "all" : intent.itemHint());
				run(manager, ctx, viaLlm, "vas hacia el cofre a guardar lo que te pidió", "store_in_chest", a, "*va hacia el cofre*", "*no encuentra un cofre*");
			}
			case PRAISE -> {
				if (body != null) body.setMood(100);
				manager.speaker().say(pick("Gracias, mi rey. Por fin alguien reconoce el talento. *mueve la cola*", "Mi rey, vos sí que sabés apreciar la facha.", "Vos también sos lo más, mi rey. No te agrandés, eh."),
					player, false, true);
			}
			case GREETING -> manager.speaker().say(pick("¡Buenas, mi rey! Llegó la estrella del servidor. Sí, hablo de mí. *mueve la cola*", "Che, mi rey, ¿jugamos o viniste a admirar mi facha?",
				"¡Mi rey! Qué alegría verte, " + name + ". *mueve la cola*"), player, false, true);
			case UNKNOWN -> {
				if (body != null && RANDOM.nextBoolean()) body.bark();
				manager.speaker().say(pick("Mi rey, hoy tengo la neurona de franco. Probá con una orden cortita.", "Bancá, mi rey, no te entendí. ¿Me lo decís más simple? *inclina la cabeza*",
					"Mi rey, se me trabó el cerebro perruno. Te escucho de nuevo."), player, false, false);
			}
		}
	}

	/** Devuelve a quien pide objetos que él mismo dio a Dante; el id exacto evita ambigüedades. */
	public static void giveOwn(DanteManager manager, ServerPlayer player, String itemId, int quantity) {
		ToolContext ctx = new ToolContext(manager, player);
		ctx.setOwnItemsOnly(true);
		JsonObject a = args("player", player.getGameProfile().getName());
		a.addProperty("item", itemId);
		if (quantity > 0) a.addProperty("quantity", quantity);
		run(manager, ctx, true, "le devolviste lo que era suyo", "give_item", a, "*te devuelve lo que es tuyo, mi rey*", "*ladea la cabeza*");
	}

	private static JsonObject args(String key, String value) {
		JsonObject o = new JsonObject();
		o.addProperty(key, value);
		return o;
	}

	private static LlmMessage.ToolResult call(DanteManager manager, ToolContext ctx, String tool, JsonObject args) {
		return manager.tools().execute(ctx, new ToolCall("fallback", tool, args));
	}

	private static void run(DanteManager manager, ToolContext ctx, boolean viaLlm, String did, String tool, JsonObject args, String success, String failure) {
		LlmMessage.ToolResult r = call(manager, ctx, tool, args);
		ServerPlayer who = ctx.speaker();
		String asker = who == null ? "Un jugador" : who.getGameProfile().getName();
		if (r.isError()) {
			String fallback = failure + " (" + r.content() + ")";
			if (viaLlm) manager.conversation().react(who, asker + " te pidió algo que no pudiste hacer. Motivo: " + r.content() + ". Contáselo.", fallback);
			else manager.speaker().say(fallback, who, false, false);
		} else if (viaLlm) {
			manager.conversation().react(who, asker + " te dio una orden y ya la cumpliste: " + did + ".", success);
		} else {
			manager.speaker().say(success, who, false, true);
		}
	}

	private static String pick(String... options) {
		return options[RANDOM.nextInt(options.length)];
	}

	/** Frase espontánea predefinida según el contexto. */
	public static String spontaneousLine(DanteManager manager, ServerPlayer target, Random random) {
		String name = target.getGameProfile().getName();
		DanteEntity body = manager.body();
		boolean night = body != null && body.level().isNight();
		boolean rain = body != null && body.level().isRaining();
		List<String> lines = new java.util.ArrayList<>(List.of(
			"Che, mi rey, " + name + ", ¿me tirás un palo? Prometo no hacerme el profesional. *mueve la cola*",
			"*olfatea el suelo* Acá pasó algo interesante...",
			"Mi rey, ¿tenés una galletita? Pregunto por un amigo cabezón. *mira fijo*",
			"*se rasca la oreja* ¡Qué lindo día para cavar pozos!",
			"Mi rey, ¿vamos a explorar? Vos llevá el mapa, yo llevo el carisma."));
		if (night) lines.add("*mira la luna* Ese zombie de allá no me cae nada bien...");
		if (rain) lines.add("*se sacude* ¡Odio mojarme las orejas!");
		if (!manager.inventory().isEmpty()) lines.add("Sigo cuidando tus cosas, eh. *palmea la mochila con la pata*");
		return lines.get(random.nextInt(lines.size()));
	}
}
