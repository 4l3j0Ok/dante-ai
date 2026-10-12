package dev.danteai.chat;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Intérprete de órdenes por palabras clave (español rioplatense/neutro e inglés) usado cuando el LLM está
 * desactivado o no responde. Permite que Dante siga siendo útil sin servidor de IA.
 */
public final class FallbackIntentParser {
	public enum Type {
		FOLLOW, STOP_FOLLOW, SIT, STAND, COME, WANDER, INVENTORY, RETURN_ALL, GIVE_ITEM, TAKE_HELD, PICKUP, STORE_CHEST, GREETING, PRAISE, SILENCE, UNKNOWN
	}

	public record Intent(Type type, String itemHint) {
	}

	private record Rule(Type type, Pattern pattern) {
	}

	private static final List<Rule> RULES = List.of(
		rule(Type.SILENCE, "callate|cállate|cayate|silencio|shh+|shut up|be quiet"),
		rule(Type.STOP_FOLLOW, "(deja|dejá|deje|para|pará|basta|stop)( de)? seguir(me|nos)?|no me sigas|stop following"),
		rule(Type.RETURN_ALL, "devolve(me)? todo|devolveme todo|dame todo|devuelveme todo|devuélveme todo|give (me )?everything|return everything|todas mis cosas"),
		rule(Type.INVENTORY, "que tenes|qué tenés|que tienes|qué tienes|que llevas|qué llevás|inventario|what do you have|encima"),
		rule(Type.STORE_CHEST, "guarda(lo|los|las)? en (el|un) (cofre|barril)|al cofre|store .* chest"),
		rule(Type.TAKE_HELD, "guardame|guárdame|tené esto|tene esto|toma esto|tomá esto|agarra esto|agarrá esto|hold this|keep this"),
		rule(Type.GIVE_ITEM, "devolveme|devuélveme|devuelveme|dame|pasame|pásame|traeme|traéme|give me|bring me"),
		rule(Type.PICKUP, "recog[eé]|levant[aá]|agarr[aá] (eso|ese|esa)|pick ?up|fetch|busca la|buscá la"),
		rule(Type.SIT, "sentate|siéntate|sientate|sit|quieto|quedate|quedate acá|quedate aca|quedate ahí|quedate ahi|stay|echate|échate|acostate"),
		rule(Type.STAND, "parate|párate|levantate|levántate|arriba|stand up|get up"),
		rule(Type.FOLLOW, "segui(me)?|seguime|sígueme|sigueme|veni conmigo|vení conmigo|venite conmigo|ven conmigo|vamos conmigo|follow( me)?|acompañame|acompáñame"),
		rule(Type.COME, "veni|vení|venite|ven aca|ven acá|aca|acá|come here|here boy"),
		rule(Type.WANDER, "anda a pasear|andá a pasear|pasea|paseá|explora|explorá|go play|wander"),
		rule(Type.PRAISE, "buen perro|good boy|que lindo|qué lindo|te quiero|perrito"),
		rule(Type.GREETING, "hola|buenas|hello|hi|hey|como estas|cómo estás|que haces|qué hacés|que estas haciendo|qué estás haciendo")
	);

	private FallbackIntentParser() {
	}

	private static Rule rule(Type type, String regex) {
		return new Rule(type, Pattern.compile("(?:^|[^\\p{L}])(?:" + regex + ")(?:$|[^\\p{L}])",
			Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
	}

	public static Intent parse(String message) {
		String text = " " + message.strip() + " ";
		for (Rule r : RULES) {
			if (r.pattern.matcher(text).find()) {
				return new Intent(r.type, r.type == Type.GIVE_ITEM || r.type == Type.TAKE_HELD || r.type == Type.STORE_CHEST
					? itemHint(message) : "");
			}
		}
		return new Intent(Type.UNKNOWN, "");
	}

	/** Palabra significativa tras el verbo, ej. "devolveme mis diamantes" → "diamantes". */
	static String itemHint(String message) {
		String[] words = message.toLowerCase().replaceAll("[^\\p{L}\\p{N}_ ]", " ").strip().split("\\s+");
		List<String> stop = List.of("dante", "devolveme", "devuelveme", "dame", "pasame", "traeme", "guardame", "give", "me", "my", "the",
			"mis", "mi", "los", "las", "el", "la", "un", "una", "unos", "unas", "estos", "estas", "este", "esta", "esto", "porfa", "por",
			"favor", "please", "tene", "toma", "agarra", "hold", "keep", "this", "en", "cofre", "al", "guarda", "guardalo", "guardalos");
		for (int i = words.length - 1; i >= 0; i--) {
			if (!words[i].isBlank() && !stop.contains(words[i]) && words[i].length() > 2) return words[i];
		}
		return "";
	}
}
