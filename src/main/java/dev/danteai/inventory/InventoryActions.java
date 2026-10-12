package dev.danteai.inventory;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Transferencias reales de objetos entre Dante, jugadores y contenedores. Conservación estricta: cada objeto que
 * sale de un lado entra en otro o se suelta en el mundo como {@link ItemEntity}; nunca se duplica ni se descarta.
 */
public final class InventoryActions {
	/** Resultado legible: "5x minecraft:diamond, 1x minecraft:bone". */
	public record Transfer(int total, Map<String, Integer> byItem, int dropped) {
		public String describe() {
			if (total == 0) return "nada";
			StringBuilder sb = new StringBuilder();
			byItem.forEach((k, v) -> sb.append(sb.isEmpty() ? "" : ", ").append(v).append("x ").append(k));
			return sb.toString();
		}
	}

	private InventoryActions() {
	}

	private static void tally(Map<String, Integer> map, ItemStack stack, int count) {
		map.merge(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), count, Integer::sum);
	}

	/** Entrega las pilas al jugador; lo que no entra en su inventario se suelta a sus pies, reservado para él. */
	public static Transfer giveToPlayer(ServerPlayer player, List<OwnedStack> stacks) {
		Map<String, Integer> byItem = new LinkedHashMap<>();
		int total = 0;
		int dropped = 0;
		for (OwnedStack owned : stacks) {
			ItemStack stack = owned.stack();
			if (stack.isEmpty()) continue;
			int count = stack.getCount();
			ItemStack sample = stack.copy();
			player.getInventory().add(stack);
			if (!stack.isEmpty()) {
				dropped += stack.getCount();
				ItemEntity item = new ItemEntity(player.level(), player.getX(), player.getY() + 0.3, player.getZ(), stack.copy());
				item.setTarget(player.getUUID());
				item.setPickUpDelay(0);
				player.level().addFreshEntity(item);
				stack.setCount(0);
			}
			tally(byItem, sample, count);
			total += count;
		}
		if (total > 0) {
			player.level().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.4F, 1.2F);
		}
		return new Transfer(total, byItem, dropped);
	}

	/** Toma del inventario del jugador (que lo pidió) hasta {@code quantity} objetos, limitado por el espacio de Dante. */
	public static Transfer takeFromPlayer(ServerPlayer player, Predicate<ItemStack> matcher, int quantity, DanteInventory target) {
		Inventory inv = player.getInventory();
		int remaining = quantity <= 0 ? Integer.MAX_VALUE : quantity;
		Map<String, Integer> byItem = new LinkedHashMap<>();
		int total = 0;
		// Primero la mano principal, después el resto.
		List<Integer> order = new ArrayList<>();
		order.add(inv.selected);
		for (int i = 0; i < inv.getContainerSize(); i++) if (i != inv.selected) order.add(i);
		for (int slot : order) {
			if (remaining <= 0) break;
			ItemStack stack = inv.getItem(slot);
			if (stack.isEmpty() || !matcher.test(stack)) continue;
			int room = target.roomFor(stack, player.getUUID());
			int move = Math.min(Math.min(remaining, stack.getCount()), room);
			if (move <= 0) continue;
			ItemStack taken = inv.removeItem(slot, move);
			int count = taken.getCount();
			ItemStack sample = taken.copy();
			target.insert(taken, player.getUUID(), player.getGameProfile().getName());
			if (!taken.isEmpty()) {
				// No debería pasar porque se calculó el espacio, pero por las dudas se devuelve.
				inv.add(taken);
				count -= taken.getCount();
				if (!taken.isEmpty()) {
					ItemEntity back = new ItemEntity(player.level(), player.getX(), player.getY() + 0.3, player.getZ(), taken.copy());
					back.setTarget(player.getUUID());
					player.level().addFreshEntity(back);
					taken.setCount(0);
				}
			}
			tally(byItem, sample, count);
			total += count;
			remaining -= count;
		}
		inv.setChanged();
		return new Transfer(total, byItem, 0);
	}

	/** Recoge una entidad de objeto del suelo hacia el inventario de Dante. */
	public static Transfer pickUp(ItemEntity item, DanteInventory target, UUID owner, String ownerName, Entity collector) {
		ItemStack stack = item.getItem();
		if (stack.isEmpty() || item.isRemoved()) return new Transfer(0, Map.of(), 0);
		ItemStack sample = stack.copy();
		ItemStack moving = stack.copy();
		int inserted = target.insert(moving, owner, ownerName);
		if (inserted <= 0) return new Transfer(0, Map.of(), 0);
		if (moving.isEmpty()) {
			item.discard();
		} else {
			item.setItem(moving);
		}
		collector.level().playSound(null, collector.getX(), collector.getY(), collector.getZ(), SoundEvents.ITEM_PICKUP,
			SoundSource.NEUTRAL, 0.3F, 1.4F);
		Map<String, Integer> byItem = new LinkedHashMap<>();
		tally(byItem, sample, inserted);
		return new Transfer(inserted, byItem, 0);
	}

	/** Mueve pilas de Dante a un contenedor; lo que no entra vuelve a Dante. */
	public static Transfer storeInContainer(Container container, List<OwnedStack> stacks, DanteInventory source) {
		Map<String, Integer> byItem = new LinkedHashMap<>();
		int total = 0;
		List<OwnedStack> leftovers = new ArrayList<>();
		for (OwnedStack owned : stacks) {
			ItemStack stack = owned.stack();
			int before = stack.getCount();
			ItemStack sample = stack.copy();
			insertInto(container, stack);
			int moved = before - stack.getCount();
			if (moved > 0) {
				tally(byItem, sample, moved);
				total += moved;
			}
			if (!stack.isEmpty()) leftovers.add(owned);
		}
		source.restore(leftovers);
		container.setChanged();
		return new Transfer(total, byItem, 0);
	}

	private static void insertInto(Container container, ItemStack stack) {
		for (int i = 0; i < container.getContainerSize() && !stack.isEmpty(); i++) {
			ItemStack existing = container.getItem(i);
			if (!existing.isEmpty() && ItemStack.isSameItemSameComponents(existing, stack) && container.canPlaceItem(i, stack)) {
				int limit = Math.min(container.getMaxStackSize(existing), existing.getMaxStackSize());
				int move = Math.min(limit - existing.getCount(), stack.getCount());
				if (move > 0) {
					existing.grow(move);
					stack.shrink(move);
				}
			}
		}
		for (int i = 0; i < container.getContainerSize() && !stack.isEmpty(); i++) {
			if (container.getItem(i).isEmpty() && container.canPlaceItem(i, stack)) {
				int move = Math.min(container.getMaxStackSize(stack), stack.getCount());
				container.setItem(i, stack.split(move));
			}
		}
	}

	/** Saca de un contenedor hacia Dante (limitado por su espacio). Los objetos quedan a nombre de {@code owner}. */
	public static Transfer takeFromContainer(Container container, Predicate<ItemStack> matcher, int quantity, DanteInventory target,
			UUID owner, String ownerName) {
		int remaining = quantity <= 0 ? Integer.MAX_VALUE : quantity;
		Map<String, Integer> byItem = new LinkedHashMap<>();
		int total = 0;
		for (int i = 0; i < container.getContainerSize() && remaining > 0; i++) {
			ItemStack stack = container.getItem(i);
			if (stack.isEmpty() || !matcher.test(stack)) continue;
			int move = Math.min(Math.min(remaining, stack.getCount()), target.roomFor(stack, owner));
			if (move <= 0) continue;
			ItemStack taken = container.removeItem(i, move);
			ItemStack sample = taken.copy();
			int count = taken.getCount();
			target.insert(taken, owner, ownerName);
			if (!taken.isEmpty()) {
				int left = taken.getCount();
				insertInto(container, taken);
				count -= left - taken.getCount();
				if (!taken.isEmpty()) {
					target.restore(List.of(new OwnedStack(taken.copy(), owner, ownerName)));
					taken.setCount(0);
				}
			}
			tally(byItem, sample, count);
			total += count;
			remaining -= count;
		}
		container.setChanged();
		return new Transfer(total, byItem, 0);
	}
}
