package dev.danteai.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.danteai.inventory.DanteInventory;
import dev.danteai.inventory.OwnedStack;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Estado global de Dante guardado con el mundo ({@code data/dante_state.dat}). Es la única fuente de verdad
 * para su identidad (UUID canónico), su inventario y su estado interno: así sobrevive a reinicios, cambios de
 * dimensión, muertes y chunks descargados sin duplicar objetos.
 */
public final class DanteWorldData extends SavedData {
	private record Snapshot(Optional<UUID> uuid, String dimension, double x, double y, double z, List<OwnedStack> inventory,
			boolean removedByAdmin, double energy, double social, double curiosity, long respawnAt) {
	}

	private static final Codec<Snapshot> SNAPSHOT_CODEC = RecordCodecBuilder.create(i -> i.group(
		UUIDUtil.CODEC.optionalFieldOf("uuid").forGetter(Snapshot::uuid),
		Codec.STRING.optionalFieldOf("dimension", "minecraft:overworld").forGetter(Snapshot::dimension),
		Codec.DOUBLE.optionalFieldOf("x", 0.0).forGetter(Snapshot::x),
		Codec.DOUBLE.optionalFieldOf("y", 0.0).forGetter(Snapshot::y),
		Codec.DOUBLE.optionalFieldOf("z", 0.0).forGetter(Snapshot::z),
		DanteInventory.CODEC.optionalFieldOf("inventory", List.of()).forGetter(Snapshot::inventory),
		Codec.BOOL.optionalFieldOf("removed_by_admin", false).forGetter(Snapshot::removedByAdmin),
		Codec.DOUBLE.optionalFieldOf("energy", 0.8).forGetter(Snapshot::energy),
		Codec.DOUBLE.optionalFieldOf("social", 0.5).forGetter(Snapshot::social),
		Codec.DOUBLE.optionalFieldOf("curiosity", 0.5).forGetter(Snapshot::curiosity),
		Codec.LONG.optionalFieldOf("respawn_at", -1L).forGetter(Snapshot::respawnAt)
	).apply(i, Snapshot::new));

	public static final SavedData.Factory<DanteWorldData> FACTORY = new SavedData.Factory<>(
		DanteWorldData::new, DanteWorldData::load, null);

	private UUID danteUuid;
	private String dimension = "minecraft:overworld";
	private double x;
	private double y;
	private double z;
	private boolean removedByAdmin;
	private double energy = 0.8;
	private double social = 0.5;
	private double curiosity = 0.5;
	private long respawnAt = -1;
	private final DanteInventory inventory = new DanteInventory(18);

	public DanteWorldData() {
		inventory.setOnChange(this::setDirty);
	}

	private static DanteWorldData load(CompoundTag tag, HolderLookup.Provider registries) {
		return fromSnapshot(SNAPSHOT_CODEC.parse(registries.createSerializationContext(NbtOps.INSTANCE), tag)
			.getOrThrow());
	}

	@Override
	public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
		DataResult<CompoundTag> encoded = SNAPSHOT_CODEC.encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), toSnapshot())
			.map(value -> (CompoundTag) value);
		return encoded.getOrThrow();
	}

	private static DanteWorldData fromSnapshot(Snapshot s) {
		DanteWorldData d = new DanteWorldData();
		d.danteUuid = s.uuid().orElse(null);
		d.dimension = s.dimension();
		d.x = s.x();
		d.y = s.y();
		d.z = s.z();
		d.inventory.load(s.inventory());
		d.removedByAdmin = s.removedByAdmin();
		d.energy = s.energy();
		d.social = s.social();
		d.curiosity = s.curiosity();
		d.respawnAt = s.respawnAt();
		return d;
	}

	private Snapshot toSnapshot() {
		return new Snapshot(Optional.ofNullable(danteUuid), dimension, x, y, z, inventory.snapshot(), removedByAdmin,
			energy, social, curiosity, respawnAt);
	}

	public UUID danteUuid() {
		return danteUuid;
	}

	public void setDanteUuid(UUID uuid) {
		this.danteUuid = uuid;
		setDirty();
	}

	public String dimension() {
		return dimension;
	}

	public double x() {
		return x;
	}

	public double y() {
		return y;
	}

	public double z() {
		return z;
	}

	public void setLastPosition(String dimension, double x, double y, double z) {
		if (!dimension.equals(this.dimension) || Math.abs(x - this.x) > 0.5 || Math.abs(y - this.y) > 0.5 || Math.abs(z - this.z) > 0.5) {
			this.dimension = dimension;
			this.x = x;
			this.y = y;
			this.z = z;
			setDirty();
		}
	}

	public DanteInventory inventory() {
		return inventory;
	}

	public boolean removedByAdmin() {
		return removedByAdmin;
	}

	public void setRemovedByAdmin(boolean removed) {
		this.removedByAdmin = removed;
		setDirty();
	}

	public double energy() {
		return energy;
	}

	public double social() {
		return social;
	}

	public double curiosity() {
		return curiosity;
	}

	public void setNeeds(double energy, double social, double curiosity) {
		this.energy = energy;
		this.social = social;
		this.curiosity = curiosity;
		setDirty();
	}

	public long respawnAt() {
		return respawnAt;
	}

	public void setRespawnAt(long gameTime) {
		this.respawnAt = gameTime;
		setDirty();
	}
}
