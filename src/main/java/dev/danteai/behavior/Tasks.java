package dev.danteai.behavior;

import dev.danteai.entity.DanteEntity;
import dev.danteai.inventory.DanteInventory;
import dev.danteai.inventory.InventoryActions;
import dev.danteai.inventory.OwnedStack;
import dev.danteai.world.ContainerAccess;
import dev.danteai.world.DanteManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/** Encargos concretos. Todos operan sobre el mundo real al llegar a destino y anuncian el resultado. */
public final class Tasks {
	private Tasks() {
	}

	/** Movimiento común: recalcula la ruta cada medio segundo y detecta atascos. */
	abstract static class MovingTask implements DanteTask {
		private int repath;
		private int stuckTicks;
		private double lastDist = Double.MAX_VALUE;

		/** @return true si llegó */
		protected boolean moveTowards(DanteEntity body, double x, double y, double z, double reach, double speed) {
			double dist = body.position().distanceTo(new net.minecraft.world.phys.Vec3(x, y, z));
			if (dist <= reach) {
				body.getNavigation().stop();
				body.getLookControl().setLookAt(x, y, z);
				return true;
			}
			if (--repath <= 0) {
				repath = 10;
				body.getNavigation().moveTo(x, y, z, 1, speed);
			}
			if (dist >= lastDist - 0.05) stuckTicks++;
			else stuckTicks = 0;
			lastDist = dist;
			return false;
		}

		protected boolean stuck() {
			return stuckTicks > 20 * 8;
		}
	}

	/** Camina hasta un jugador y le entrega objetos del inventario de Dante al llegar. */
	public static final class Deliver extends MovingTask {
		private final UUID player;
		private final String playerName;
		private final Predicate<ItemStack> matcher;
		private final Predicate<OwnedStack> allowed;
		private final int quantity;
		private final String itemLabel;

		public Deliver(ServerPlayer player, Predicate<ItemStack> matcher, Predicate<OwnedStack> allowed, int quantity, String itemLabel) {
			this.player = player.getUUID();
			this.playerName = player.getGameProfile().getName();
			this.matcher = matcher;
			this.allowed = allowed;
			this.quantity = quantity;
			this.itemLabel = itemLabel;
		}

		@Override
		public String describe() {
			return "llevarle " + itemLabel + " a " + playerName;
		}

		@Override
		public Status tick(DanteEntity body, ServerLevel level, DanteManager manager) {
			ServerPlayer target = manager.server().getPlayerList().getPlayer(player);
			if (target == null || target.level() != level) {
				manager.speaker().emote(manager.text("no encuentra a " + playerName + " y se queda con las cosas"), null);
				return Status.FAILURE;
			}
			if (stuck()) {
				manager.speaker().emote(manager.text("no puede llegar hasta " + playerName), target);
				return Status.FAILURE;
			}
			if (!moveTowards(body, target.getX(), target.getY(), target.getZ(), manager.config().inventory.give_reach - 0.5, 1.25)) {
				return Status.RUNNING;
			}
			DanteInventory inv = manager.inventory();
			List<OwnedStack> stacks = inv.extract(matcher, allowed, quantity, player);
			if (stacks.isEmpty()) {
				manager.speaker().emote(manager.text("busca " + itemLabel + " pero ya no tiene"), target);
				return Status.FAILURE;
			}
			InventoryActions.Transfer t = InventoryActions.giveToPlayer(target, stacks);
			manager.onItemsGiven(target, t);
			return Status.SUCCESS;
		}
	}

	/** Camina hasta un objeto en el suelo y lo guarda. */
	public static final class Collect extends MovingTask {
		private final UUID itemEntity;
		private final UUID requester;
		private final String requesterName;

		public Collect(ItemEntity item, ServerPlayer requester) {
			this.itemEntity = item.getUUID();
			this.requester = requester == null ? null : requester.getUUID();
			this.requesterName = requester == null ? "" : requester.getGameProfile().getName();
		}

		@Override
		public String describe() {
			return "recoger un objeto del suelo";
		}

		@Override
		public Status tick(DanteEntity body, ServerLevel level, DanteManager manager) {
			Entity e = level.getEntity(itemEntity);
			if (!(e instanceof ItemEntity item) || item.isRemoved()) {
				return Status.FAILURE;
			}
			if (stuck()) return Status.FAILURE;
			if (!moveTowards(body, item.getX(), item.getY(), item.getZ(), 1.4, 1.2)) {
				return Status.RUNNING;
			}
			UUID owner = requester;
			String ownerName = requesterName;
			Entity thrower = item.getOwner();
			if (thrower instanceof Player p) {
				owner = p.getUUID();
				ownerName = p.getGameProfile().getName();
			}
			InventoryActions.Transfer t = InventoryActions.pickUp(item, manager.inventory(), owner, ownerName, body);
			if (t.total() == 0) {
				manager.speaker().emote(manager.text("no tiene más lugar en su mochila"), null);
				return Status.FAILURE;
			}
			manager.onItemsCollected(t, owner, ownerName);
			return Status.SUCCESS;
		}
	}

	/** Guarda o retira objetos de un contenedor en nombre de un jugador. */
	public static final class Container extends MovingTask {
		public enum Mode { STORE, TAKE }

		private final BlockPos pos;
		private final Mode mode;
		private final UUID requester;
		private final String requesterName;
		private final Predicate<ItemStack> matcher;
		private final Predicate<OwnedStack> allowed;
		private final int quantity;

		public Container(BlockPos pos, Mode mode, ServerPlayer requester, Predicate<ItemStack> matcher, Predicate<OwnedStack> allowed, int quantity) {
			this.pos = pos;
			this.mode = mode;
			this.requester = requester.getUUID();
			this.requesterName = requester.getGameProfile().getName();
			this.matcher = matcher;
			this.allowed = allowed;
			this.quantity = quantity;
		}

		@Override
		public String describe() {
			return (mode == Mode.STORE ? "guardar cosas en el cofre de " : "sacar cosas del cofre de ") + pos.toShortString();
		}

		@Override
		public Status tick(DanteEntity body, ServerLevel level, DanteManager manager) {
			if (stuck()) {
				manager.speaker().emote(manager.text("no puede llegar al cofre"), null);
				return Status.FAILURE;
			}
			if (!moveTowards(body, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 2.3, 1.15)) {
				return Status.RUNNING;
			}
			ServerPlayer player = manager.server().getPlayerList().getPlayer(requester);
			if (player == null) return Status.FAILURE;
			Object access = ContainerAccess.check(level, pos, player, manager.config().chests);
			if (access instanceof ContainerAccess.Denied denied) {
				manager.speaker().emote(manager.text("no puede usar ese cofre (" + denied.reason() + ")"), player);
				return Status.FAILURE;
			}
			ContainerAccess.Found found = (ContainerAccess.Found) access;
			InventoryActions.Transfer t;
			if (mode == Mode.STORE) {
				List<OwnedStack> stacks = manager.inventory().extract(matcher, allowed, quantity, requester);
				t = InventoryActions.storeInContainer(found.container(), stacks, manager.inventory());
			} else {
				t = InventoryActions.takeFromContainer(found.container(), matcher, quantity, manager.inventory(), requester, requesterName);
			}
			manager.onContainerTransfer(player, mode == Mode.STORE, t, pos);
			return t.total() > 0 ? Status.SUCCESS : Status.FAILURE;
		}
	}

	/** Se acerca a un jugador y se sienta a su lado (o solo lo mira). */
	public static final class Approach extends MovingTask {
		private final UUID player;

		public Approach(ServerPlayer player) {
			this.player = player.getUUID();
		}

		@Override
		public String describe() {
			return "acercarse a un jugador";
		}

		@Override
		public Status tick(DanteEntity body, ServerLevel level, DanteManager manager) {
			ServerPlayer target = manager.server().getPlayerList().getPlayer(player);
			if (target == null || target.level() != level || stuck()) return Status.FAILURE;
			if (moveTowards(body, target.getX(), target.getY(), target.getZ(), 2.2, 1.2)) {
				body.getLookControl().setLookAt(target);
				return Status.SUCCESS;
			}
			return Status.RUNNING;
		}

		@Override
		public int timeoutTicks() {
			return 20 * 40;
		}
	}
}
