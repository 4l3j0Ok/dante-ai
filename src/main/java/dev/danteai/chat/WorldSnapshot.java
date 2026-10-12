package dev.danteai.chat;

import dev.danteai.behavior.DanteBrain;
import dev.danteai.entity.DanteEntity;
import dev.danteai.inventory.OwnedStack;
import dev.danteai.world.DanteManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Descripción textual compacta del estado del mundo alrededor de Dante. Se captura en el hilo del servidor
 * (único lugar seguro para leer el mundo) y luego viaja como texto inmutable hacia el hilo de red del LLM.
 */
public final class WorldSnapshot {
	private WorldSnapshot() {
	}

	public static String capture(DanteManager manager, DanteEntity body, ServerPlayer speaker) {
		StringBuilder sb = new StringBuilder();
		DanteBrain brain = manager.brain();
		if (body == null) {
			sb.append("- Tu cuerpo está lejos, en una zona sin jugadores (no podés moverte hasta que alguien se acerque o te teletransportes).\n");
		} else {
			ServerLevel level = (ServerLevel) body.level();
			BlockPos pos = body.blockPosition();
			sb.append("- Estás en ").append(dimensionName(level)).append('\n');
			level.getBiome(pos).unwrapKey().ifPresent(k -> sb.append("- Bioma: ").append(k.location().getPath()).append('\n'));
			long time = level.getDayTime() % 24000L;
			sb.append("- Hora: ").append(timeOfDay(time)).append(level.isThundering() ? ", tormenta" : level.isRaining() ? ", lloviendo" : ", despejado").append('\n');
			sb.append("- Actividad actual: ").append(brain.describeActivity());
			if (body.getDantePose() == DanteEntity.POSE_SITTING) sb.append(" (sentado)");
			if (body.getDantePose() == DanteEntity.POSE_SLEEPING) sb.append(" (dormido)");
			sb.append(". Energía ").append(pct(brain.needs().energy())).append(", ganas de compañía ").append(pct(brain.needs().social()))
				.append(", curiosidad ").append(pct(brain.needs().curiosity())).append('\n');
			appendNearby(sb, body, level, speaker);
		}
		appendInventory(sb, manager, speaker);
		appendPlayers(sb, manager, body);
		return sb.toString();
	}

	static String pct(double v) {
		return Math.round(v * 100) + "%";
	}

	public static String dimensionName(ServerLevel level) {
		String id = level.dimension().location().toString();
		return switch (id) {
			case "minecraft:overworld" -> "el Overworld";
			case "minecraft:the_nether" -> "el Nether";
			case "minecraft:the_end" -> "el End";
			default -> id;
		};
	}

	static String timeOfDay(long t) {
		int hours = (int) ((t / 1000 + 6) % 24);
		int minutes = (int) ((t % 1000) * 60 / 1000);
		String phase = t < 1000 ? "amanecer" : t < 11000 ? "día" : t < 13000 ? "atardecer" : t < 23000 ? "noche" : "amanecer";
		return String.format(Locale.ROOT, "%02d:%02d (%s)", hours, minutes, phase);
	}

	private static void appendNearby(StringBuilder sb, DanteEntity body, ServerLevel level, ServerPlayer speaker) {
		AABB box = body.getBoundingBox().inflate(16, 6, 16);
		Map<String, Integer> mobs = new TreeMap<>();
		List<String> items = new ArrayList<>();
		List<Entity> entities = level.getEntities(body, box, e -> e.isAlive() && !(e instanceof Player));
		entities.sort(Comparator.comparingDouble(e -> e.distanceToSqr(body)));
		for (Entity e : entities) {
			if (e instanceof ItemEntity item) {
				if (items.size() < 8) {
					ItemStack s = item.getItem();
					items.add("[id " + e.getId() + "] " + s.getCount() + "x " + BuiltInRegistries.ITEM.getKey(s.getItem()) + " a "
						+ Math.round(e.distanceTo(body)) + " bloques");
				}
			} else if (e instanceof LivingEntity) {
				String name = e.getType().getDescription().getString() + (e instanceof Monster ? " (hostil)" : "");
				mobs.merge(name, 1, Integer::sum);
			}
		}
		if (!mobs.isEmpty()) {
			sb.append("- Criaturas cerca: ");
			mobs.forEach((k, v) -> sb.append(v).append("x ").append(k).append("; "));
			sb.append('\n');
		}
		if (!items.isEmpty()) {
			sb.append("- Objetos en el suelo (podés usar collect_item con el id): ").append(String.join("; ", items)).append('\n');
		}
		Map<String, Integer> blocks = new LinkedHashMap<>();
		BlockPos origin = body.blockPosition();
		for (BlockPos p : BlockPos.betweenClosed(origin.offset(-5, -2, -5), origin.offset(5, 2, 5))) {
			BlockState s = level.getBlockState(p);
			String id = BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
			if (isNotable(id)) blocks.merge(id, 1, Integer::sum);
		}
		if (!blocks.isEmpty()) {
			sb.append("- Bloques notables alrededor: ");
			blocks.forEach((k, v) -> sb.append(k).append(v > 1 ? " x" + v : "").append(", "));
			sb.setLength(sb.length() - 2);
			sb.append('\n');
		}
		if (speaker != null && speaker.level() == level) {
			ItemStack held = speaker.getMainHandItem();
			sb.append("- ").append(speaker.getGameProfile().getName()).append(" está a ").append(Math.round(speaker.distanceTo(body)))
				.append(" bloques").append(held.isEmpty() ? "" : " con " + held.getCount() + "x " + BuiltInRegistries.ITEM.getKey(held.getItem()) + " en la mano")
				.append('\n');
		}
	}

	private static boolean isNotable(String id) {
		return id.contains("chest") || id.contains("barrel") || id.equals("lava") || id.equals("fire") || id.contains("bed")
			|| id.contains("ore") || id.contains("campfire") || id.equals("crafting_table") || id.contains("furnace") || id.equals("bell")
			|| id.contains("door") || id.equals("water");
	}

	private static void appendInventory(StringBuilder sb, DanteManager manager, ServerPlayer speaker) {
		List<OwnedStack> slots = manager.inventory().slots();
		sb.append("- Tu inventario (").append(slots.size()).append('/').append(manager.inventory().capacity()).append(" espacios): ");
		if (slots.isEmpty()) {
			sb.append("vacío\n");
			return;
		}
		List<String> parts = new ArrayList<>();
		for (OwnedStack s : slots) {
			String owner = s.owner() == null ? "sin dueño" : speaker != null && s.ownedBy(speaker.getUUID()) ? "de quien te habla" : "de " + s.ownerName();
			parts.add(s.stack().getCount() + "x " + BuiltInRegistries.ITEM.getKey(s.stack().getItem()) + " (" + owner + ")");
		}
		sb.append(String.join("; ", parts)).append('\n');
	}

	private static void appendPlayers(StringBuilder sb, DanteManager manager, DanteEntity body) {
		List<String> parts = new ArrayList<>();
		for (ServerPlayer p : manager.server().getPlayerList().getPlayers()) {
			String where;
			if (body == null) {
				where = dimensionName(p.serverLevel());
			} else if (p.level() != body.level()) {
				where = "en " + dimensionName(p.serverLevel());
			} else {
				where = "a " + Math.round(p.distanceTo(body)) + " bloques";
			}
			parts.add(p.getGameProfile().getName() + " (" + where + ")");
			if (parts.size() >= 15) break;
		}
		sb.append("- Jugadores conectados: ").append(parts.isEmpty() ? "ninguno" : String.join(", ", parts)).append('\n');
	}
}
