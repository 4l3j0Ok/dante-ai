package dev.danteai.chat;

import dev.danteai.behavior.VisitScheduler.Reply;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Interpreta la respuesta a «voy a visitar a otro jugador». Las frases claras («quedate», «no te vayas», «andá»)
 * valen siempre; las ambiguas («sí», «dale», «no», «chau») solo cuando el mensaje va dirigido a Dante.
 */
public final class VisitReplyParser {
	private static final Set<String> FILLER = Set.of("dante", "che", "mi", "rey", "reina", "porfa", "porfavor", "por", "favor",
		"amigo", "gracias", "pls", "please", "ya");

	private static final Pattern STAY_STRICT = Pattern.compile("no te vayas( todavia| aun| ahora)?|no te me vayas|quedate( un rato| mas| aca| conmigo| otro rato| nomas)?|no te muevas");
	private static final Pattern STAY_LOOSE = Pattern.compile("no|noo+|nah|todavia no|aun no|esperate|espera");
	private static final Pattern GO_STRICT = Pattern.compile("anda|andate|anda nomas|andate nomas|anda a (visitarlo|visitarla|verlo|verla)");
	private static final Pattern GO_LOOSE = Pattern.compile("si|dale|ok|okey|bueno|listo|obvio|claro|chau|nos vemos|vaya|ve|dale anda|dale andate|anda dale|andate dale");

	private VisitReplyParser() {
	}

	/** @return la respuesta, o {@code null} si el mensaje no es una respuesta al aviso */
	public static Reply parse(String message, Collection<String> names, boolean loose) {
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
		if (STAY_STRICT.matcher(text).matches()) return Reply.STAY;
		if (GO_STRICT.matcher(text).matches()) return Reply.GO;
		if (loose) {
			if (STAY_LOOSE.matcher(text).matches()) return Reply.STAY;
			if (GO_LOOSE.matcher(text).matches()) return Reply.GO;
		}
		return null;
	}
}
