package dev.danteai.chat;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Órdenes de movimiento inequívocas que se ejecutan sin pasar por el LLM. Solo coincide si el mensaje completo
 * (sin saludos ni muletillas) es la orden; todo lo demás, incluidas preguntas, sigue al modelo.
 */
public final class DirectCommandParser {
	public enum Command {
		FOLLOW, STOP_FOLLOW, SIT, STAND, COME, WANDER, SILENCE
	}

	private record Rule(Command command, Pattern pattern) {
	}

	private static final Set<String> FILLER = Set.of("dante", "che", "porfa", "porfavor", "please", "pls", "dale", "ya", "ahora",
		"mi", "rey", "reina", "por", "favor", "amigo", "perro", "perrito", "gracias", "ok", "bueno");

	private static final List<Rule> RULES = List.of(
		rule(Command.STOP_FOLLOW, "(deja|dejame|para|basta)( de)? (seguir|seguirme|seguirnos)( mas)?|no me sigas( mas)?|stop following( me)?"),
		rule(Command.FOLLOW, "seguime|veni conmigo|venite conmigo|ven conmigo|acompaname|follow me|vamos conmigo|vamos juntos"),
		rule(Command.COME, "veni|venite|ven|veni aca|venite aca|ven aca|veni para aca|acercate|come here"),
		rule(Command.SIT, "sentate|sientate|sit|sit down|quieto|quieta|quedate quieto|quedate quieta|quedate|quedate aca|quedate ahi|echate|acostate|stay"),
		rule(Command.STAND, "parate|levantate|arriba|stand up|get up"),
		rule(Command.WANDER, "anda a pasear|pasea|explora|anda a explorar|anda a jugar|go play|go explore"),
		rule(Command.SILENCE, "callate|cayate|silencio|shh+|chito|no hables( mas)?|quedate callado|quedate callada|para|basta|shut up|be quiet")
	);

	private DirectCommandParser() {
	}

	private static Rule rule(Command command, String regex) {
		return new Rule(command, Pattern.compile("(?:" + regex + ")"));
	}

	/** @return la orden, o {@code null} si el mensaje no es una orden directa y clara */
	public static Command parse(String message, Collection<String> names) {
		if (message == null || message.indexOf('?') >= 0 || message.indexOf('¿') >= 0) return null;
		Set<String> ignored = new HashSet<>(FILLER);
		for (String name : names) {
			for (String token : TextUtil.fold(name).split(" ")) if (!token.isEmpty()) ignored.add(token);
		}
		List<String> kept = new ArrayList<>();
		for (String token : TextUtil.fold(message).split(" ")) {
			if (!token.isEmpty() && !ignored.contains(token)) kept.add(token);
		}
		if (kept.isEmpty() || kept.size() > 5) return null;
		String text = String.join(" ", kept);
		for (Rule r : RULES) {
			if (r.pattern.matcher(text).matches()) return r.command;
		}
		return null;
	}
}
