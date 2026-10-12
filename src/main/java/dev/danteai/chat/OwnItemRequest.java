package dev.danteai.chat;

import dev.danteai.inventory.ItemMatcher;
import dev.danteai.inventory.OwnedStack;
import dev.danteai.world.DanteManager;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pedidos claros de devolver objetos que el propio jugador le dio a Dante («devolvemela», «dame mi espada»,
 * «devolveme 3 diamantes», «devolveme todo»). Se resuelven sin pasar por el LLM, que a veces describe la entrega
 * sin llamar a la herramienta. Lo ambiguo (una palabra que no es un objeto suyo) sigue al modelo.
 */
public final class OwnItemRequest {
	private static final Set<String> VERBS = Set.of("dame", "pasame", "traeme", "give", "bring");
	private static final Set<String> ALL_WORDS = Set.of("todo", "todas", "todos", "cosas");
	private static final Set<String> STOP = Set.of("dante", "che", "porfa", "porfavor", "por", "favor", "mi", "mis", "me", "el", "la",
		"lo", "los", "las", "un", "una", "de", "del", "al", "a", "en", "y", "tu", "tus", "vos", "ya", "ahora", "rey", "reina", "perro",
		"perrito", "please", "my", "the", "it", "them", "te", "le", "se", "esa", "ese", "eso", "esas", "esos", "this", "that", "amigo");
	private static final Pattern NUMBER = Pattern.compile("(?<![\\p{L}\\d])(\\d{1,3})(?![\\p{L}\\d])");

	private OwnItemRequest() {
	}

	/** @return {@code true} si el mensaje era un pedido de devolución de lo propio y ya se atendió */
	public static boolean handle(DanteManager manager, ServerPlayer player, String message) {
		if (!isReturnRequest(message)) return false;
		List<OwnedStack> mine = manager.inventory().slots().stream().filter(s -> s.ownedBy(player.getUUID())).toList();
		if (mine.isEmpty()) return false;
		List<String> words = meaningfulWords(message, player.getGameProfile().getName());
		if (words.stream().anyMatch(ALL_WORDS::contains)) {
			FallbackResponder.respond(manager, player, new FallbackIntentParser.Intent(FallbackIntentParser.Type.RETURN_ALL, ""), true);
			return true;
		}
		String itemId;
		if (words.isEmpty()) {
			List<String> kinds = mine.stream().map(s -> itemId(s.stack())).distinct().toList();
			if (kinds.size() != 1) return false;
			itemId = kinds.get(0);
		} else {
			Predicate<ItemStack> wanted = ItemMatcher.parse(String.join(" ", words));
			if (wanted == null) return false;
			Optional<OwnedStack> match = mine.stream().filter(s -> wanted.test(s.stack())).findFirst();
			if (match.isEmpty()) return false;
			itemId = itemId(match.get().stack());
		}
		FallbackResponder.giveOwn(manager, player, itemId, quantity(message));
		return true;
	}

	/** Palabras que describen el objeto: sin verbo, sin muletillas, sin el nombre del jugador ni números. */
	public static List<String> meaningfulWords(String message, String playerName) {
		String name = TextUtil.fold(playerName);
		List<String> out = new ArrayList<>();
		for (String token : TextUtil.fold(message).split(" ")) {
			if (token.isEmpty() || isVerb(token) || STOP.contains(token) || token.matches("\\d+") || token.equals(name)) continue;
			out.add(token);
		}
		return out;
	}

	/** Cantidad pedida (primer número del mensaje); 0 significa todo lo que tenga de ese objeto. */
	public static int quantity(String message) {
		Matcher m = NUMBER.matcher(TextUtil.fold(message));
		return m.find() ? Integer.parseInt(m.group(1)) : 0;
	}

	private static boolean isReturnRequest(String message) {
		if (message == null || message.indexOf('?') >= 0 || message.indexOf('¿') >= 0) return false;
		for (String token : TextUtil.fold(message).split(" ")) {
			if (isVerb(token)) return true;
		}
		return false;
	}

	private static boolean isVerb(String token) {
		return token.startsWith("devolv") || token.startsWith("devuelv") || VERBS.contains(token);
	}

	private static String itemId(ItemStack stack) {
		return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
	}
}
