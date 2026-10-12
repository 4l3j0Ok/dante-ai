package dev.danteai.inventory;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Resuelve la descripción de un objeto que da el modelo o el jugador ("minecraft:diamond", "diamond",
 * "diamantes", "Espada de hierro") en un predicado sobre pilas.
 */
public final class ItemMatcher {
	/** Alias en español de objetos comunes (el servidor solo conoce los nombres en inglés). */
	private static final Map<String, String> ALIASES = Map.ofEntries(
		Map.entry("diamante", "diamond"), Map.entry("hierro", "iron_ingot"), Map.entry("lingote de hierro", "iron_ingot"),
		Map.entry("oro", "gold_ingot"), Map.entry("lingote de oro", "gold_ingot"), Map.entry("esmeralda", "emerald"),
		Map.entry("carbon", "coal"), Map.entry("madera", "oak_planks"), Map.entry("tronco", "oak_log"), Map.entry("piedra", "cobblestone"),
		Map.entry("roca", "cobblestone"), Map.entry("tierra", "dirt"), Map.entry("arena", "sand"), Map.entry("vidrio", "glass"),
		Map.entry("hueso", "bone"), Map.entry("palo", "stick"), Map.entry("manzana", "apple"), Map.entry("pan", "bread"),
		Map.entry("zanahoria", "carrot"), Map.entry("papa", "potato"), Map.entry("patata", "potato"), Map.entry("trigo", "wheat"),
		Map.entry("huevo", "egg"), Map.entry("antorcha", "torch"), Map.entry("flecha", "arrow"), Map.entry("arco", "bow"),
		Map.entry("cuerda", "string"), Map.entry("lana", "white_wool"), Map.entry("cuero", "leather"), Map.entry("plumas", "feather"),
		Map.entry("pluma", "feather"), Map.entry("redstone", "redstone"), Map.entry("lapislazuli", "lapis_lazuli"),
		Map.entry("cobre", "copper_ingot"), Map.entry("netherita", "netherite_ingot"), Map.entry("perla", "ender_pearl"),
		Map.entry("carne", "beef"), Map.entry("filete", "cooked_beef"), Map.entry("pollo", "chicken"), Map.entry("pescado", "cod"),
		Map.entry("galleta", "cookie"), Map.entry("pastel", "cake"), Map.entry("torta", "cake"), Map.entry("libro", "book"),
		Map.entry("papel", "paper"), Map.entry("balde", "bucket"), Map.entry("cubeta", "bucket"), Map.entry("cofre", "chest"),
		Map.entry("espada", "sword"), Map.entry("pico", "pickaxe"), Map.entry("hacha", "axe"), Map.entry("pala", "shovel"),
		Map.entry("azada", "hoe"), Map.entry("casco", "helmet"), Map.entry("pechera", "chestplate"), Map.entry("pantalones", "leggings"),
		Map.entry("botas", "boots"), Map.entry("escudo", "shield"), Map.entry("totem", "totem_of_undying"), Map.entry("cama", "bed")
	);

	private ItemMatcher() {
	}

	/** Devuelve un predicado o {@code null} si la descripción está vacía o significa "todo". */
	public static Predicate<ItemStack> parse(String description) {
		if (description == null) return null;
		String raw = description.strip().toLowerCase(Locale.ROOT);
		if (raw.isEmpty() || raw.equals("*") || raw.equals("all") || raw.equals("todo") || raw.equals("everything")) return null;
		ResourceLocation id = ResourceLocation.tryParse(raw.contains(":") ? raw : "minecraft:" + raw.replace(' ', '_'));
		if (id != null && BuiltInRegistries.ITEM.containsKey(id)) {
			ResourceLocation exact = id;
			return stack -> exact.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()));
		}
		String folded = fold(raw);
		String alias = aliasFor(folded);
		String needle = alias != null ? alias : singular(folded).replace(' ', '_');
		return stack -> matchesText(stack, needle, folded);
	}

	private static String aliasFor(String folded) {
		if (ALIASES.containsKey(folded)) return ALIASES.get(folded);
		String singular = singular(folded);
		if (ALIASES.containsKey(singular)) return ALIASES.get(singular);
		// "espada de hierro" → "iron_sword"
		String[] parts = singular.split(" de ");
		if (parts.length == 2) {
			String tool = ALIASES.get(singular(parts[0].strip()));
			String material = ALIASES.get(singular(parts[1].strip()));
			if (tool != null && material != null) return material.replace("_ingot", "") + "_" + tool;
		}
		return null;
	}

	private static boolean matchesText(ItemStack stack, String needle, String folded) {
		ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
		String path = key.getPath();
		if (path.equals(needle) || path.contains(needle)) return true;
		String name = fold(stack.getHoverName().getString().toLowerCase(Locale.ROOT));
		return name.equals(folded) || name.contains(folded) || name.replace(' ', '_').contains(needle);
	}

	static String singular(String word) {
		String w = word.strip();
		if (w.endsWith("es") && w.length() > 4 && !w.endsWith("ies")) {
			String withoutEs = w.substring(0, w.length() - 2);
			if (ALIASES.containsKey(withoutEs)) return withoutEs;
		}
		if (w.endsWith("s") && w.length() > 3) return w.substring(0, w.length() - 1);
		return w;
	}

	static String fold(String s) {
		return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
	}

	public static String describe(ItemStack stack) {
		ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
		return stack.getCount() + "x " + key + " (" + stack.getHoverName().getString() + ")";
	}
}
