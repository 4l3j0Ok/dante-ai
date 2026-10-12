package dev.danteai.entity;

import dev.danteai.DanteAi;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

public final class ModEntities {
	public static final ResourceKey<EntityType<?>> DANTE_KEY = ResourceKey.create(Registries.ENTITY_TYPE, DanteAi.id("dante"));

	public static final EntityType<DanteEntity> DANTE = Registry.register(BuiltInRegistries.ENTITY_TYPE, DANTE_KEY,
		EntityType.Builder.<DanteEntity>of(DanteEntity::new, MobCategory.MISC)
			.sized(0.8F, 1.2F)
			.eyeHeight(1.0F)
			.clientTrackingRange(10)
			.build(DANTE_KEY.location().toString()));

	private ModEntities() {
	}

	public static void register() {
		FabricDefaultAttributeRegistry.register(DANTE, DanteEntity.createAttributes());
	}
}
