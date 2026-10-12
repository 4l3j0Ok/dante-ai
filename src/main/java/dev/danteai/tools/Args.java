package dev.danteai.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.List;

/** Lectura validada de argumentos JSON y construcción de esquemas JSON Schema. */
public final class Args {
	private Args() {
	}

	public static String string(JsonObject args, String name, boolean required, int maxLength) throws ToolException {
		JsonElement e = args.get(name);
		if (e == null || e.isJsonNull()) {
			if (required) throw new ToolException("falta el argumento '" + name + "'");
			return "";
		}
		if (!e.isJsonPrimitive()) throw new ToolException("'" + name + "' debe ser texto");
		String s = e.getAsString().strip();
		if (required && s.isEmpty()) throw new ToolException("'" + name + "' está vacío");
		return s.length() > maxLength ? s.substring(0, maxLength) : s;
	}

	/** Entero opcional. Acepta números como texto ("3"); "all"/"todo" o ausencia devuelven {@code def}. */
	public static int integer(JsonObject args, String name, int def, int min, int max) throws ToolException {
		JsonElement e = args.get(name);
		if (e == null || e.isJsonNull()) return def;
		if (!e.isJsonPrimitive()) throw new ToolException("'" + name + "' debe ser un número");
		JsonPrimitive p = e.getAsJsonPrimitive();
		try {
			if (p.isString()) {
				String s = p.getAsString().strip().toLowerCase(java.util.Locale.ROOT);
				if (s.isEmpty() || s.equals("all") || s.equals("todo") || s.equals("todos")) return def;
				return clamp((int) Double.parseDouble(s), min, max);
			}
			return clamp((int) p.getAsDouble(), min, max);
		} catch (NumberFormatException ex) {
			throw new ToolException("'" + name + "' debe ser un número");
		}
	}

	private static int clamp(int v, int min, int max) {
		return Math.max(min, Math.min(max, v));
	}

	// --- esquemas ---

	public static JsonObject schema(JsonObject properties, String... required) {
		JsonObject o = new JsonObject();
		o.addProperty("type", "object");
		o.add("properties", properties);
		JsonArray req = new JsonArray();
		for (String r : required) req.add(r);
		o.add("required", req);
		return o;
	}

	public static JsonObject props() {
		return new JsonObject();
	}

	public static JsonObject stringProp(String description) {
		JsonObject o = new JsonObject();
		o.addProperty("type", "string");
		o.addProperty("description", description);
		return o;
	}

	public static JsonObject intProp(String description) {
		JsonObject o = new JsonObject();
		o.addProperty("type", "integer");
		o.addProperty("description", description);
		return o;
	}

	public static JsonObject enumProp(String description, List<String> values) {
		JsonObject o = stringProp(description);
		JsonArray arr = new JsonArray();
		values.forEach(arr::add);
		o.add("enum", arr);
		return o;
	}
}
