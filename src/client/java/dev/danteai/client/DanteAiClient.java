package dev.danteai.client;

import dev.danteai.DanteAi;
import dev.danteai.entity.ModEntities;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.EntityModelLayerRegistry;
import net.minecraft.client.model.geom.ModelLayerLocation;

public final class DanteAiClient implements ClientModInitializer {
	public static final ModelLayerLocation DANTE_LAYER = new ModelLayerLocation(DanteAi.id("dante"), "main");

	@Override
	public void onInitializeClient() {
		EntityModelLayerRegistry.registerModelLayer(DANTE_LAYER, DanteModel::createBodyLayer);
		EntityRendererRegistry.register(ModEntities.DANTE, DanteRenderer::new);
	}
}
