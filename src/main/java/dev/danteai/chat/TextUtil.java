package dev.danteai.chat;

import java.util.ArrayList;
import java.util.List;

/** Saneamiento y formateo de texto para chat y voz. */
public final class TextUtil {
	private TextUtil() {
	}

	/**
	 * Limpia la salida del modelo para el chat de Minecraft: quita códigos de formato (§), markdown, saltos de línea
	 * y prefijos del tipo "Dante:" que algunos modelos agregan.
	 */
	public static String sanitizeForChat(String text, String name) {
		if (text == null) return "";
		String s = text.replaceAll("\u00A7.?", "")
			.replaceAll("(?s)<think>.*?</think>", "")
			.replaceAll("```[a-zA-Z]*", "")
			.replace("**", "")
			.replace("__", "")
			.replaceAll("[\\r\\n\\t]+", " ")
			.replaceAll("\\p{Cntrl}", "")
			.replaceAll("\\s{2,}", " ")
			.strip();
		String prefix = name + ":";
		if (s.regionMatches(true, 0, prefix, 0, prefix.length())) {
			s = s.substring(prefix.length()).strip();
		}
		if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
			s = s.substring(1, s.length() - 1).strip();
		}
		return s;
	}

	/** Divide un mensaje largo en partes de como máximo {@code max} caracteres respetando palabras. */
	public static List<String> split(String text, int max, int maxParts) {
		List<String> parts = new ArrayList<>();
		String rest = text.strip();
		while (!rest.isEmpty() && parts.size() < maxParts) {
			if (rest.length() <= max) {
				parts.add(rest);
				rest = "";
				break;
			}
			int cut = rest.lastIndexOf(' ', max);
			if (cut < max / 2) cut = max;
			parts.add(rest.substring(0, cut).strip());
			rest = rest.substring(cut).strip();
		}
		if (!rest.isEmpty() && !parts.isEmpty()) {
			String last = parts.removeLast();
			parts.add(last.length() > max - 1 ? last.substring(0, max - 1) + "…" : last + "…");
		}
		return parts;
	}

	/**
	 * Texto apto para leer en voz alta: quita acciones entre asteriscos ({@code *mueve la cola*}) sin importar su largo,
	 * asteriscos sueltos, emojis y símbolos que el TTS leería literalmente.
	 */
	public static String speakable(String text) {
		if (text == null) return "";
		String s = text.replaceAll("\\*[^*]+\\*", " ")
			.replace('*', ' ')
			.replaceAll("\\([^)]{0,200}\\)", " ")
			.replaceAll("[\\p{So}\\p{Cs}\\p{Sk}]", " ")
			.replaceAll("[_#~`|<>\\[\\]{}]", " ")
			.replaceAll("(^|\\s)[.,;:]+(?=\\s|$)", " ")
			.replaceAll("\\s{2,}", " ")
			.strip();
		return s.matches("[\\p{Punct}\\s]*") ? "" : s;
	}

	/**
	 * Recorta un texto para leerlo en voz alta sin dejar una frase a medias: corta en el último final de frase que entre
	 * en {@code max} (o, si no hay, en el último espacio) y no agrega «…», que el TTS leería como una pausa rara.
	 */
	public static String truncateForSpeech(String text, int max) {
		if (text == null) return "";
		String s = text.strip();
		if (s.length() <= max) return s;
		String head = s.substring(0, max);
		int half = max / 2;
		int cut = -1;
		for (int i = head.length() - 1; i >= half; i--) {
			char c = head.charAt(i);
			if (c == '.' || c == '!' || c == '?' || c == '…') {
				cut = i + 1;
				break;
			}
		}
		if (cut < 0) {
			int space = head.lastIndexOf(' ');
			cut = space >= half ? space : max;
		}
		return head.substring(0, cut).strip();
	}

	public static String truncate(String text, int max) {
		if (text == null) return "";
		return text.length() <= max ? text : text.substring(0, Math.max(0, max - 1)) + "…";
	}

	/** Minúsculas, sin tildes ni símbolos y con espacios simples: sirve para comparar textos. */
	public static String fold(String text) {
		if (text == null) return "";
		String n = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
		return n.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", " ").strip();
	}

	/** {@code true} si dos mensajes dicen lo mismo (iguales o uno contenido en el otro, ignorando formato). */
	public static boolean sameMessage(String a, String b) {
		String fa = fold(a);
		String fb = fold(b);
		if (fa.isEmpty() || fb.isEmpty()) return false;
		if (fa.equals(fb)) return true;
		int min = Math.min(fa.length(), fb.length());
		return min >= 20 && (fa.contains(fb) || fb.contains(fa));
	}
}
