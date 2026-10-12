package dev.danteai.chat;

import java.text.Normalizer;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detecta si un mensaje de chat está dirigido a Dante: {@code @Dante} en cualquier parte, o el nombre al comienzo
 * seguido de puntuación o espacio ({@code "Dante, vení"}, {@code "Dante vení"}, {@code "Ey Dante!"}).
 */
public final class MentionDetector {
	public record Result(boolean addressed, String cleanedMessage) {
	}

	private final List<Pattern> anywhere;
	private final List<Pattern> leading;

	public MentionDetector(List<String> names) {
		this.anywhere = names.stream()
			.map(n -> Pattern.compile("@" + Pattern.quote(fold(n)) + "\\b[,:]?", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE))
			.toList();
		this.leading = names.stream()
			.map(n -> Pattern.compile("^\\s*(?:(?:ey|eh|hey|che|hola|oye|oi|hi)[\\s,!]+)?" + Pattern.quote(fold(n)) + "(?:[\\s,:!?.¡¿]+|$)",
				Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE))
			.toList();
	}

	public Result detect(String message) {
		String folded = fold(message);
		for (Pattern p : anywhere) {
			Matcher m = p.matcher(folded);
			if (m.find()) {
				return new Result(true, clean(message, m.start(), m.end()));
			}
		}
		for (Pattern p : leading) {
			Matcher m = p.matcher(folded);
			if (m.find()) {
				String rest = message.substring(Math.min(message.length(), m.end())).strip();
				return new Result(true, rest.isEmpty() ? message.strip() : rest);
			}
		}
		return new Result(false, message.strip());
	}

	/** Indica si el texto contiene el nombre (sin requerir '@'), útil para transcripciones de voz. */
	public boolean containsName(String message, List<String> names) {
		String folded = fold(message);
		for (String n : names) {
			if (Pattern.compile("\\b" + Pattern.quote(fold(n)) + "\\b", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(folded).find()) {
				return true;
			}
		}
		return false;
	}

	private static String clean(String message, int start, int end) {
		String cleaned = (message.substring(0, start) + " " + message.substring(Math.min(end, message.length()))).strip();
		cleaned = cleaned.replaceAll("\\s{2,}", " ");
		return cleaned.isEmpty() ? message.strip() : cleaned;
	}

	/**
	 * Quita acentos para comparar, manteniendo la misma longitud que el original (cada carácter acentuado
	 * se convierte en uno solo), de modo que los índices del texto plegado sirven sobre el original.
	 */
	static String fold(String s) {
		StringBuilder sb = new StringBuilder(s.length());
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			String d = Normalizer.normalize(String.valueOf(c), Normalizer.Form.NFD);
			char base = d.isEmpty() ? c : d.charAt(0);
			sb.append(Character.toLowerCase(base));
		}
		return sb.toString();
	}
}
