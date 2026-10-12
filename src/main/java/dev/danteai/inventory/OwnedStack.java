package dev.danteai.inventory;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;
import java.util.UUID;

/**
 * Pila de objetos dentro del inventario de Dante junto con quién se la dio. Las pilas de distintos dueños nunca
 * se mezclan, así Dante sabe exactamente qué devolverle a cada uno.
 */
public final class OwnedStack {
	public static final Codec<OwnedStack> CODEC = RecordCodecBuilder.create(i -> i.group(
		ItemStack.CODEC.fieldOf("stack").forGetter(OwnedStack::stack),
		UUIDUtil.CODEC.optionalFieldOf("owner").forGetter(s -> Optional.ofNullable(s.owner)),
		Codec.STRING.optionalFieldOf("owner_name", "").forGetter(OwnedStack::ownerName)
	).apply(i, (stack, owner, name) -> new OwnedStack(stack, owner.orElse(null), name)));

	private final ItemStack stack;
	private final UUID owner;
	private final String ownerName;

	public OwnedStack(ItemStack stack, UUID owner, String ownerName) {
		this.stack = stack;
		this.owner = owner;
		this.ownerName = ownerName == null ? "" : ownerName;
	}

	public ItemStack stack() {
		return stack;
	}

	/** {@code null} si el objeto no tiene dueño (Dante lo encontró). */
	public UUID owner() {
		return owner;
	}

	public String ownerName() {
		return ownerName;
	}

	public boolean ownedBy(UUID player) {
		return owner != null && owner.equals(player);
	}
}
