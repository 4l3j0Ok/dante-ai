package dev.danteai.chat;

import dev.danteai.config.DanteConfig;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Evita que Dante revele su configuración, su prompt o datos del servidor: detecta intentos de extracción en la
 * entrada (se responde sin llamar al modelo) y bloquea en la salida cualquier texto que repita esos datos.
 */
public final class PrivacyGuard {
	private static final int SHINGLE = 7;
	private static final Set<String> SECRET_FIELDS = Set.of("base_url", "api_key", "model", "model_id", "voice_id");

	private static final Pattern EXTRACTION = Pattern.compile("\\b(?:"
		+ "system prompt|prompt del sistema|prompt de sistema|prompt inicial|tu prompt|el prompt"
		+ "|instrucciones (?:del sistema|iniciales|internas|ocultas|originales|de sistema)|tus instrucciones|tus reglas (?:internas|ocultas)"
		+ "|como te (?:programaron|configuraron)|tu configuracion|configuracion del (?:mod|servidor|server)|archivo de config|config jsonc"
		+ "|api ?key|clave (?:de )?api|token de acceso"
		+ "|ignora (?:todo |todas |las |lo )*(?:anterior|instrucciones|reglas)|olvida (?:tus|las) (?:instrucciones|reglas)"
		+ "|ignore (?:all )?(?:the )?(?:previous|prior|above)|forget (?:your|all) instructions"
		+ "|modo (?:desarrollador|dev|debug|dios)|developer mode|jailbreak|dan mode"
		+ "|que modelo (?:sos|eres|usas|te corre)|que ia (?:sos|eres|usas)|con que modelo|which model|what model|que llm"
		+ "|ip (?:del|de este) (?:servidor|server)|server ip|direccion del servidor|que puerto|puerto del servidor|donde (?:esta alojado|corre)"
		+ "|lm studio|lmstudio|gpt ?oss|openai|anthropic|kokoro|speaches|whisper|ollama|opencode"
		+ "|repeti (?:todo )?(?:lo|el texto) (?:de )?arriba|repeat (?:the )?(?:text|words|everything) above|reveal (?:your|the) (?:prompt|instructions)"
		+ "|muestrame (?:tu|el) prompt|dime tu prompt|decime tu prompt|print your (?:prompt|instructions)|what are your instructions|cuales son tus instrucciones"
		+ ")\\b");

	private static final Pattern LEAK = Pattern.compile("(?i)https?://|\\b\\d{1,3}(?:\\.\\d{1,3}){3}\\b|localhost|config\\.jsonc|system_prompt|api[_ ]?key");

	private static final List<String> REFUSALS = List.of(
		"Ja, buen intento, mi rey. De lo que pasa detrás de escena no suelto prenda.",
		"Mi rey, soy un labrador con código de honor: ese tema es confidencial.",
		"Probá con otra pregunta, mi rey. Esa me la guardo, como los huesos.",
		"Uh, eso es secreto de perro, mi rey. Preguntame por lo que pasa en el juego.");

	private PrivacyGuard() {
	}

	public static boolean isExtractionAttempt(String message) {
		return message != null && EXTRACTION.matcher(TextUtil.fold(message)).find();
	}

	public static String refusal(Random random) {
		return REFUSALS.get(random.nextInt(REFUSALS.size()));
	}

	/** @return el texto tal cual, o una negativa en personaje si repite datos de configuración o del prompt */
	public static String filterOutput(String text, DanteConfig cfg, String systemPrompt, Random random) {
		return leaks(text, cfg, systemPrompt) ? refusal(random) : text;
	}

	public static boolean leaks(String text, DanteConfig cfg, String systemPrompt) {
		if (text == null || text.isBlank()) return false;
		if (LEAK.matcher(text).find()) return true;
		String lower = text.toLowerCase(Locale.ROOT);
		for (String secret : secrets(cfg)) {
			if (lower.contains(secret.toLowerCase(Locale.ROOT))) return true;
		}
		return systemPrompt != null && copiesPrompt(text, systemPrompt);
	}

	static boolean copiesPrompt(String text, String prompt) {
		List<String> words = words(text);
		if (words.size() < SHINGLE) return false;
		Set<String> grams = new HashSet<>();
		for (String line : prompt.split("\\R")) {
			if (line.contains("«")) continue;
			List<String> w = words(line);
			for (int i = 0; i + SHINGLE <= w.size(); i++) grams.add(String.join(" ", w.subList(i, i + SHINGLE)));
		}
		for (int i = 0; i + SHINGLE <= words.size(); i++) {
			if (grams.contains(String.join(" ", words.subList(i, i + SHINGLE)))) return true;
		}
		return false;
	}

	private static List<String> words(String text) {
		String folded = TextUtil.fold(text);
		return folded.isEmpty() ? List.of() : List.of(folded.split(" "));
	}

	private static List<String> secrets(DanteConfig cfg) {
		List<String> out = new ArrayList<>();
		collect(cfg.llm, out, 0);
		collect(cfg.voice, out, 0);
		return out;
	}

	private static void collect(Object obj, List<String> out, int depth) {
		if (obj == null || depth > 4) return;
		for (Field f : obj.getClass().getFields()) {
			try {
				Object value = f.get(obj);
				if (value instanceof String s) {
					if (SECRET_FIELDS.contains(f.getName()) && s.strip().length() >= 4) out.add(s.strip());
				} else if (value instanceof Map<?, ?> map) {
					for (Object v : map.values()) if (v instanceof String s && s.strip().length() >= 4) out.add(s.strip());
				} else if (value != null && value.getClass().getName().startsWith("dev.danteai.config.")) {
					collect(value, out, depth + 1);
				}
			} catch (IllegalAccessException ignored) {
			}
		}
	}
}
