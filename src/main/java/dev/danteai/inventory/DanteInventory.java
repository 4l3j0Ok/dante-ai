package dev.danteai.inventory;

import com.mojang.serialization.Codec;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Inventario propio de Dante. Todas las operaciones mueven pilas reales: lo que entra se resta de su origen y lo
 * que sale se entrega como {@link ItemStack} al llamador, que debe colocarlo en el mundo. Nunca se crean ni se
 * destruyen objetos acá.
 */
public final class DanteInventory {
	public static final Codec<List<OwnedStack>> CODEC = OwnedStack.CODEC.listOf();

	private final List<OwnedStack> slots = new ArrayList<>();
	private int capacity;
	private Runnable onChange = () -> {
	};

	public DanteInventory(int capacity) {
		this.capacity = Math.max(1, capacity);
	}

	public void setOnChange(Runnable onChange) {
		this.onChange = Objects.requireNonNull(onChange);
	}

	public int capacity() {
		return capacity;
	}

	/** Cambiar la capacidad nunca borra objetos: si sobran, simplemente no entra nada nuevo hasta que se liberen. */
	public void setCapacity(int capacity) {
		this.capacity = Math.max(1, capacity);
	}

	public List<OwnedStack> slots() {
		return Collections.unmodifiableList(slots);
	}

	public boolean isEmpty() {
		return slots.isEmpty();
	}

	public int freeSlots() {
		return Math.max(0, capacity - slots.size());
	}

	public void load(List<OwnedStack> stacks) {
		slots.clear();
		for (OwnedStack s : stacks) {
			if (s != null && !s.stack().isEmpty()) slots.add(s);
		}
	}

	/**
	 * Inserta la pila (la modifica: queda con lo que no entró). Se combina solo con pilas del mismo dueño.
	 *
	 * @return cantidad insertada
	 */
	public int insert(ItemStack stack, UUID owner, String ownerName) {
		if (stack.isEmpty()) return 0;
		int inserted = 0;
		for (OwnedStack slot : slots) {
			if (stack.isEmpty()) break;
			if (!Objects.equals(slot.owner(), owner) || !ItemStack.isSameItemSameComponents(slot.stack(), stack)) continue;
			int room = slot.stack().getMaxStackSize() - slot.stack().getCount();
			if (room <= 0) continue;
			int move = Math.min(room, stack.getCount());
			slot.stack().grow(move);
			stack.shrink(move);
			inserted += move;
		}
		while (!stack.isEmpty() && slots.size() < capacity) {
			int move = Math.min(stack.getMaxStackSize(), stack.getCount());
			slots.add(new OwnedStack(stack.split(move), owner, ownerName));
			inserted += move;
		}
		if (inserted > 0) onChange.run();
		return inserted;
	}

	/** Cuántos objetos que cumplen {@code matcher} entrarían si se intentara insertar {@code sample}. */
	public int roomFor(ItemStack sample, UUID owner) {
		int room = freeSlots() * sample.getMaxStackSize();
		for (OwnedStack slot : slots) {
			if (Objects.equals(slot.owner(), owner) && ItemStack.isSameItemSameComponents(slot.stack(), sample)) {
				room += Math.max(0, slot.stack().getMaxStackSize() - slot.stack().getCount());
			}
		}
		return room;
	}

	public int count(Predicate<ItemStack> matcher, Predicate<OwnedStack> allowed) {
		int total = 0;
		for (OwnedStack slot : slots) {
			if (matcher.test(slot.stack()) && allowed.test(slot)) total += slot.stack().getCount();
		}
		return total;
	}

	/**
	 * Extrae hasta {@code quantity} objetos que cumplan ambos filtros. Si {@code quantity <= 0} extrae todos.
	 * Prioriza las pilas del dueño preferido (por ejemplo, devolverle primero lo suyo a quien pide).
	 */
	public List<OwnedStack> extract(Predicate<ItemStack> matcher, Predicate<OwnedStack> allowed, int quantity, UUID preferredOwner) {
		List<OwnedStack> candidates = new ArrayList<>();
		for (OwnedStack slot : slots) {
			if (matcher.test(slot.stack()) && allowed.test(slot)) candidates.add(slot);
		}
		candidates.sort((a, b) -> Boolean.compare(!a.ownedBy(preferredOwner), !b.ownedBy(preferredOwner)));
		int remaining = quantity <= 0 ? Integer.MAX_VALUE : quantity;
		List<OwnedStack> out = new ArrayList<>();
		for (OwnedStack slot : candidates) {
			if (remaining <= 0) break;
			int take = Math.min(remaining, slot.stack().getCount());
			out.add(new OwnedStack(slot.stack().split(take), slot.owner(), slot.ownerName()));
			remaining -= take;
			if (slot.stack().isEmpty()) slots.remove(slot);
		}
		if (!out.isEmpty()) onChange.run();
		return out;
	}

	/**
	 * Devuelve a Dante lo que no se pudo entregar, conservando el dueño original. Nunca se pierde nada: si no hay
	 * lugar, la pila se agrega igual por encima de la capacidad.
	 */
	public void restore(List<OwnedStack> stacks) {
		for (OwnedStack s : stacks) {
			if (s.stack().isEmpty()) continue;
			ItemStack copy = s.stack().copy();
			s.stack().setCount(0);
			insert(copy, s.owner(), s.ownerName());
			if (!copy.isEmpty()) {
				slots.add(new OwnedStack(copy, s.owner(), s.ownerName()));
				onChange.run();
			}
		}
	}

	public List<OwnedStack> extractAll() {
		List<OwnedStack> out = new ArrayList<>(slots);
		slots.clear();
		if (!out.isEmpty()) onChange.run();
		return out;
	}

	public List<OwnedStack> snapshot() {
		List<OwnedStack> copy = new ArrayList<>(slots.size());
		for (OwnedStack s : slots) copy.add(new OwnedStack(s.stack().copy(), s.owner(), s.ownerName()));
		return copy;
	}
}
