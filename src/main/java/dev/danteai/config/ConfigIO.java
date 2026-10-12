package dev.danteai.config;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;

import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Lee y escribe la configuración en formato JSON con comentarios ({@code .jsonc}).
 * <p>
 * Al leer se aceptan comentarios {@code //} y {@code /* *}{@code /}. Al escribir, los comentarios se generan a
 * partir de las anotaciones {@link Comment}, de modo que el archivo siempre documenta todas las opciones.
 */
public final class ConfigIO {
	private static final FieldNamingPolicy NAMING = FieldNamingPolicy.IDENTITY;
	private static final Gson GSON = new GsonBuilder()
		.setFieldNamingPolicy(NAMING)
		.disableHtmlEscaping()
		.create();

	private ConfigIO() {
	}

	/** Quita las comas finales antes de {@code }} o {@code ]} (frecuentes al editar a mano); respeta strings y comentarios. */
	static String stripTrailingCommas(String text) {
		StringBuilder out = new StringBuilder(text.length());
		int i = 0;
		int n = text.length();
		boolean inString = false;
		while (i < n) {
			char c = text.charAt(i);
			if (inString) {
				out.append(c);
				if (c == '\\' && i + 1 < n) out.append(text.charAt(++i));
				else if (c == '"') inString = false;
				i++;
			} else if (c == '"') {
				inString = true;
				out.append(c);
				i++;
			} else if (c == '/' && i + 1 < n && text.charAt(i + 1) == '/') {
				int end = text.indexOf('\n', i);
				end = end < 0 ? n : end;
				out.append(text, i, end);
				i = end;
			} else if (c == '/' && i + 1 < n && text.charAt(i + 1) == '*') {
				int end = text.indexOf("*/", i + 2);
				end = end < 0 ? n : end + 2;
				out.append(text, i, end);
				i = end;
			} else if (c == ',' && nextSignificantIsClosing(text, i + 1)) {
				i++;
			} else {
				out.append(c);
				i++;
			}
		}
		return out.toString();
	}

	private static boolean nextSignificantIsClosing(String text, int from) {
		int i = from;
		int n = text.length();
		while (i < n) {
			char c = text.charAt(i);
			if (Character.isWhitespace(c)) {
				i++;
			} else if (c == '/' && i + 1 < n && text.charAt(i + 1) == '/') {
				int end = text.indexOf('\n', i);
				if (end < 0) return false;
				i = end + 1;
			} else if (c == '/' && i + 1 < n && text.charAt(i + 1) == '*') {
				int end = text.indexOf("*/", i + 2);
				if (end < 0) return false;
				i = end + 2;
			} else {
				return c == '}' || c == ']';
			}
		}
		return false;
	}

	public static DanteConfig parse(String text) throws IOException {
		JsonReader reader = new JsonReader(new StringReader(stripTrailingCommas(text)));
		reader.setLenient(true);
		JsonElement element = JsonParser.parseReader(reader);
		if (element == null || element.isJsonNull()) {
			return new DanteConfig();
		}
		DanteConfig config = GSON.fromJson(element, DanteConfig.class);
		return config == null ? new DanteConfig() : config;
	}

	public static DanteConfig loadOrCreate(Path file) throws IOException {
		DanteConfig config;
		if (Files.exists(file)) {
			config = parse(Files.readString(file, StandardCharsets.UTF_8));
		} else {
			config = new DanteConfig();
		}
		ConfigValidator.validate(config);
		write(file, config);
		return config;
	}

	public static void write(Path file, DanteConfig config) throws IOException {
		Files.createDirectories(file.toAbsolutePath().getParent());
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.writeString(tmp, toJsonc(config), StandardCharsets.UTF_8);
		Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	public static String toJsonc(Object config) {
		StringBuilder sb = new StringBuilder();
		sb.append("// Configuración de Dante AI. Se admiten comentarios.\n");
		sb.append("// Este archivo se regenera al cargar para agregar opciones nuevas (los comentarios propios no se conservan).\n");
		sb.append("// Recargar sin reiniciar: /dante reload\n");
		writeObject(sb, config, 0);
		sb.append('\n');
		return sb.toString();
	}

	private static void writeObject(StringBuilder sb, Object obj, int indent) {
		sb.append("{\n");
		Field[] fields = obj.getClass().getFields();
		boolean first = true;
		for (Field field : fields) {
			if (Modifier.isStatic(field.getModifiers())) {
				continue;
			}
			if (!first) {
				sb.append(",\n");
			}
			first = false;
			Comment comment = field.getAnnotation(Comment.class);
			if (comment != null) {
				for (String line : comment.value().split("\n")) {
					indent(sb, indent + 1).append("// ").append(line).append('\n');
				}
			}
			indent(sb, indent + 1).append(GSON.toJson(NAMING.translateName(field))).append(": ");
			Object value;
			try {
				value = field.get(obj);
			} catch (IllegalAccessException e) {
				throw new IllegalStateException(e);
			}
			if (value != null && isSection(value.getClass())) {
				writeObject(sb, value, indent + 1);
			} else {
				sb.append(GSON.toJson(value));
			}
		}
		sb.append('\n');
		indent(sb, indent).append('}');
	}

	private static boolean isSection(Class<?> type) {
		return type.getEnclosingClass() == DanteConfig.class;
	}

	private static StringBuilder indent(StringBuilder sb, int level) {
		sb.append("\t".repeat(level));
		return sb;
	}
}
