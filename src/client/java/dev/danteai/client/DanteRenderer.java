package dev.danteai.client;

import dev.danteai.DanteAi;
import dev.danteai.entity.DanteEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.ResourceLocation;

public class DanteRenderer extends MobRenderer<DanteEntity, DanteModel> {
	private static final ResourceLocation TEXTURE = DanteAi.id("textures/entity/dante.png");

	public DanteRenderer(EntityRendererProvider.Context context) {
		super(context, new DanteModel(context.bakeLayer(DanteAiClient.DANTE_LAYER)), 0.5F);
	}

	@Override
	public ResourceLocation getTextureLocation(DanteEntity entity) {
		return TEXTURE;
	}
}
