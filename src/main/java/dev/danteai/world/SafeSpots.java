package dev.danteai.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Búsqueda de posiciones seguras para caminar o aparecer: suelo firme, sin fluidos ni bloques dañinos. */
public final class SafeSpots {
	private SafeSpots() {
	}

	public static boolean isDangerousBlock(BlockState state) {
		return state.is(Blocks.LAVA) || state.is(BlockTags.FIRE) || state.is(BlockTags.CAMPFIRES) || state.is(Blocks.MAGMA_BLOCK)
			|| state.is(Blocks.CACTUS) || state.is(Blocks.SWEET_BERRY_BUSH) || state.is(Blocks.POWDER_SNOW)
			|| state.is(Blocks.WITHER_ROSE) || state.is(Blocks.POINTED_DRIPSTONE);
	}

	/** {@code true} si el cuerpo de tipo {@code type} puede pararse con los pies en {@code feet}. */
	public static boolean isSafeStandingSpot(ServerLevel level, BlockPos feet, EntityType<?> type) {
		if (!level.isLoaded(feet) || !level.getWorldBorder().isWithinBounds(feet)) return false;
		BlockPos below = feet.below();
		BlockState ground = level.getBlockState(below);
		if (!ground.isFaceSturdy(level, below, Direction.UP) || isDangerousBlock(ground)) return false;
		for (int dy = 0; dy <= 1; dy++) {
			BlockState s = level.getBlockState(feet.above(dy));
			if (!s.getFluidState().isEmpty() || isDangerousBlock(s)) return false;
		}
		AABB box = type.getDimensions().makeBoundingBox(Vec3.atBottomCenterOf(feet));
		return level.noCollision(box);
	}

	/**
	 * Busca un lugar seguro cerca de {@code center}, priorizando distancias entre {@code minRadius} y
	 * {@code maxRadius} y alturas cercanas a la del centro.
	 */
	public static BlockPos findNear(ServerLevel level, BlockPos center, int minRadius, int maxRadius, EntityType<?> type, RandomSource random) {
		for (int attempt = 0; attempt < 48; attempt++) {
			int r = minRadius + random.nextInt(Math.max(1, maxRadius - minRadius + 1));
			double angle = random.nextDouble() * Math.PI * 2;
			int x = center.getX() + (int) Math.round(Math.cos(angle) * r);
			int z = center.getZ() + (int) Math.round(Math.sin(angle) * r);
			for (int dy : new int[] {0, 1, -1, 2, -2, 3, -3}) {
				BlockPos pos = new BlockPos(x, center.getY() + dy, z);
				if (isSafeStandingSpot(level, pos, type)) return pos;
			}
		}
		for (int dy : new int[] {0, 1, -1}) {
			BlockPos pos = center.above(dy);
			if (isSafeStandingSpot(level, pos, type)) return pos;
		}
		return null;
	}
}
