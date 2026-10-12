package dev.danteai.world;

import dev.danteai.config.DanteConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Encuentra contenedores a los que Dante puede acceder en nombre de un jugador. Dante nunca tiene más permisos
 * que el jugador que lo pide: se verifican cerraduras vanilla, protección de spawn, borde del mundo, distancia del
 * jugador y, opcionalmente, que el contenedor esté nombrado para Dante.
 */
public final class ContainerAccess {
	public record Found(BlockPos pos, Container container, String name) {
	}

	public record Denied(String reason) {
	}

	private ContainerAccess() {
	}

	public static List<BlockPos> candidates(ServerLevel level, BlockPos center, DanteConfig.Chests cfg) {
		int r = cfg.search_radius;
		List<BlockPos> out = new ArrayList<>();
		int minCx = (center.getX() - r) >> 4;
		int maxCx = (center.getX() + r) >> 4;
		int minCz = (center.getZ() - r) >> 4;
		int maxCz = (center.getZ() + r) >> 4;
		for (int cx = minCx; cx <= maxCx; cx++) {
			for (int cz = minCz; cz <= maxCz; cz++) {
				if (!level.hasChunk(cx, cz)) continue;
				LevelChunk chunk = level.getChunk(cx, cz);
				for (BlockEntity be : chunk.getBlockEntities().values()) {
					BlockPos pos = be.getBlockPos();
					if (pos.distSqr(center) > (double) r * r) continue;
					ResourceLocation id = BuiltInRegistries.BLOCK.getKey(be.getBlockState().getBlock());
					if (cfg.allowed_containers.contains(id.toString())) out.add(pos.immutable());
				}
			}
		}
		out.sort(Comparator.comparingDouble(p -> p.distSqr(center)));
		return out;
	}

	/**
	 * Verifica si {@code player} podría abrir el contenedor y devuelve el {@link Container} (incluye cofres dobles)
	 * o un motivo de rechazo.
	 */
	public static Object check(ServerLevel level, BlockPos pos, ServerPlayer player, DanteConfig.Chests cfg) {
		if (!level.getWorldBorder().isWithinBounds(pos)) return new Denied("fuera del borde del mundo");
		if (player.level() != level || player.position().distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(pos)) > cfg.max_player_distance) {
			return new Denied("el jugador tiene que estar cerca del contenedor");
		}
		if (!player.mayInteract(level, pos)) return new Denied("zona protegida");
		BlockEntity be = level.getBlockEntity(pos);
		String name = "";
		if (be instanceof BaseContainerBlockEntity base) {
			if (!base.canOpen(player)) return new Denied("el contenedor está cerrado con llave");
			name = base.getDisplayName().getString();
			if (cfg.require_named_container) {
				var custom = base.getCustomName();
				if (custom == null || !custom.getString().toLowerCase(Locale.ROOT).contains(cfg.container_keyword.toLowerCase(Locale.ROOT))) {
					return new Denied("solo puedo usar contenedores nombrados '" + cfg.container_keyword + "'");
				}
			}
		} else if (cfg.require_named_container) {
			return new Denied("el contenedor no tiene nombre");
		}
		Container container = HopperBlockEntity.getContainerAt(level, pos);
		if (container == null) return new Denied("no es un contenedor");
		return new Found(pos, container, name);
	}
}
