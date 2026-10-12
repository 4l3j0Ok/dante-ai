package dev.danteai.client;

import dev.danteai.entity.DanteEntity;
import net.minecraft.client.model.HierarchicalModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.util.Mth;

/**
 * Modelo propio de Dante: labrador negro caricaturesco con cabeza enorme (10x9x8 frente a un cuerpo de 8x7x12 y patas cortas),
 * hocico ancho con mandíbula móvil, orejas caídas, ojos grandes que sobresalen, lengua, collar y cola de nutria.
 * <p>
 * Todas las animaciones son procedurales a partir del estado sincronizado: caminar/correr, sentarse, dormir,
 * mirar, mover la cola según el ánimo, inclinar la cabeza al escuchar, hablar, jadear, ladrar, parpadear y
 * sacudirse.
 */
public class DanteModel extends HierarchicalModel<DanteEntity> {
	public static final int TEX_W = 128;
	public static final int TEX_H = 64;

	private final ModelPart root;
	private final ModelPart body;
	private final ModelPart head;
	private final ModelPart jaw;
	private final ModelPart tongue;
	private final ModelPart leftEar;
	private final ModelPart rightEar;
	private final ModelPart leftEye;
	private final ModelPart rightEye;
	private final ModelPart tail;
	private final ModelPart rightFrontLeg;
	private final ModelPart leftFrontLeg;
	private final ModelPart rightHindLeg;
	private final ModelPart leftHindLeg;

	public DanteModel(ModelPart root) {
		this.root = root;
		this.body = root.getChild("body");
		this.head = root.getChild("head");
		this.jaw = head.getChild("jaw");
		this.tongue = jaw.getChild("tongue");
		this.leftEar = head.getChild("left_ear");
		this.rightEar = head.getChild("right_ear");
		this.leftEye = head.getChild("left_eye");
		this.rightEye = head.getChild("right_eye");
		this.tail = root.getChild("tail");
		this.rightFrontLeg = root.getChild("right_front_leg");
		this.leftFrontLeg = root.getChild("left_front_leg");
		this.rightHindLeg = root.getChild("right_hind_leg");
		this.leftHindLeg = root.getChild("left_hind_leg");
	}

	/**
	 * Geometría y mapeo UV. Las coordenadas son píxeles del modelo (Y hacia abajo, -Z hacia adelante, suelo en Y=24).
	 * {@code tools/generate_texture.py} usa la misma distribución UV para pintar la textura.
	 */
	public static LayerDefinition createBodyLayer() {
		MeshDefinition mesh = new MeshDefinition();
		PartDefinition root = mesh.getRoot();

		PartDefinition body = root.addOrReplaceChild("body",
			CubeListBuilder.create().texOffs(0, 0).addBox(-4.0F, -3.5F, -6.0F, 8, 7, 12),
			PartPose.offset(0.0F, 15.0F, 1.0F));
		body.addOrReplaceChild("collar",
			CubeListBuilder.create().texOffs(0, 22).addBox(-4.5F, -4.0F, -6.5F, 9, 6, 2),
			PartPose.ZERO);

		PartDefinition head = root.addOrReplaceChild("head",
			CubeListBuilder.create().texOffs(48, 0).addBox(-5.0F, -8.0F, -7.0F, 10, 9, 8),
			PartPose.offset(0.0F, 12.5F, -5.0F));
		head.addOrReplaceChild("snout",
			CubeListBuilder.create().texOffs(84, 0).addBox(-3.0F, -3.0F, -11.0F, 6, 3, 4),
			PartPose.ZERO);
		head.addOrReplaceChild("nose",
			CubeListBuilder.create().texOffs(104, 0).addBox(-1.5F, -3.5F, -11.5F, 3, 2, 1),
			PartPose.ZERO);
		PartDefinition jaw = head.addOrReplaceChild("jaw",
			CubeListBuilder.create().texOffs(84, 8).addBox(-2.5F, 0.0F, -4.0F, 5, 1, 4),
			PartPose.offset(0.0F, 0.0F, -7.0F));
		jaw.addOrReplaceChild("tongue",
			CubeListBuilder.create().texOffs(104, 4).addBox(-1.0F, 0.6F, -4.2F, 2, 1, 3),
			PartPose.ZERO);
		head.addOrReplaceChild("left_ear",
			CubeListBuilder.create().texOffs(48, 18).addBox(0.0F, 0.0F, -2.0F, 1, 7, 4),
			PartPose.offsetAndRotation(5.0F, -7.5F, -2.5F, 0.0F, 0.0F, -0.12F));
		head.addOrReplaceChild("right_ear",
			CubeListBuilder.create().texOffs(60, 18).addBox(-1.0F, 0.0F, -2.0F, 1, 7, 4),
			PartPose.offsetAndRotation(-5.0F, -7.5F, -2.5F, 0.0F, 0.0F, 0.12F));
		head.addOrReplaceChild("left_eye",
			CubeListBuilder.create().texOffs(72, 18).addBox(1.0F, -6.5F, -7.5F, 3, 3, 1),
			PartPose.ZERO);
		head.addOrReplaceChild("right_eye",
			CubeListBuilder.create().texOffs(72, 23).addBox(-4.0F, -6.5F, -7.5F, 3, 3, 1),
			PartPose.ZERO);

		root.addOrReplaceChild("tail",
			CubeListBuilder.create().texOffs(0, 31).addBox(-1.0F, -1.0F, 0.0F, 2, 2, 8),
			PartPose.offsetAndRotation(0.0F, 12.5F, 6.5F, 0.5F, 0.0F, 0.0F));

		root.addOrReplaceChild("right_front_leg", CubeListBuilder.create().texOffs(0, 42).addBox(-1.5F, 0.0F, -1.5F, 3, 6, 3),
			PartPose.offset(-2.5F, 18.0F, -2.5F));
		root.addOrReplaceChild("left_front_leg", CubeListBuilder.create().texOffs(12, 42).addBox(-1.5F, 0.0F, -1.5F, 3, 6, 3),
			PartPose.offset(2.5F, 18.0F, -2.5F));
		root.addOrReplaceChild("right_hind_leg", CubeListBuilder.create().texOffs(24, 42).addBox(-1.5F, 0.0F, -1.5F, 3, 6, 3),
			PartPose.offset(-2.5F, 18.0F, 4.5F));
		root.addOrReplaceChild("left_hind_leg", CubeListBuilder.create().texOffs(36, 42).addBox(-1.5F, 0.0F, -1.5F, 3, 6, 3),
			PartPose.offset(2.5F, 18.0F, 4.5F));
		return LayerDefinition.create(mesh, TEX_W, TEX_H);
	}

	@Override
	public ModelPart root() {
		return root;
	}

	@Override
	public void setupAnim(DanteEntity entity, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch) {
		root.getAllParts().forEach(ModelPart::resetPose);
		float partialTicks = ageInTicks - entity.tickCount;
		DanteRenderState s = new DanteRenderState();
		s.ageInTicks = ageInTicks;
		s.walkAnimationPos = limbSwing;
		s.walkAnimationSpeed = limbSwingAmount;
		s.yRot = netHeadYaw;
		s.xRot = headPitch;
		s.sitProgress = entity.getSitProgress(partialTicks);
		s.sleepProgress = entity.getSleepProgress(partialTicks);
		s.tiltProgress = entity.getTiltProgress(partialTicks);
		s.shakeProgress = entity.getShakeProgress(partialTicks);
		s.barkProgress = entity.getBarkProgress(partialTicks);
		s.mood = entity.getMood();
		s.speaking = entity.hasFlag(DanteEntity.FLAG_SPEAKING);
		s.panting = entity.hasFlag(DanteEntity.FLAG_PANTING);
		s.excited = entity.hasFlag(DanteEntity.FLAG_EXCITED);
		s.idHash = entity.getId() & 0x3F;
		float age = s.ageInTicks;
		float walkPos = s.walkAnimationPos;
		float walkSpeed = Math.min(1.0F, s.walkAnimationSpeed);
		float sit = s.sitProgress;
		float sleep = s.sleepProgress;
		float stand = Math.max(0.0F, 1.0F - sit - sleep);

		// --- Patas: trote (más amplio al correr), con rebote del cuerpo y de la cabeza.
		float swing = 0.6662F;
		float amp = 1.25F * walkSpeed * stand;
		rightHindLeg.xRot = Mth.cos(walkPos * swing) * amp;
		leftHindLeg.xRot = Mth.cos(walkPos * swing + Mth.PI) * amp;
		rightFrontLeg.xRot = Mth.cos(walkPos * swing + Mth.PI) * amp;
		leftFrontLeg.xRot = Mth.cos(walkPos * swing) * amp;
		float bounce = Math.abs(Mth.sin(walkPos * swing)) * walkSpeed * stand;
		body.y -= bounce * 0.8F;
		head.y -= bounce * 0.6F;
		tail.y -= bounce * 0.8F;

		// --- Cabeza: mira al objetivo; se inclina de costado al escuchar (curiosidad de perro).
		head.yRot = Mth.clamp(s.yRot, -70.0F, 70.0F) * Mth.DEG_TO_RAD;
		head.xRot = s.xRot * Mth.DEG_TO_RAD;
		head.zRot = 0.38F * s.tiltProgress * (s.idHash % 2 == 0 ? 1.0F : -1.0F);
		head.y += Mth.sin(walkPos * 1.3F) * 0.4F * walkSpeed * stand;

		// --- Sentado.
		if (sit > 0.0F) {
			body.y += 2.5F * sit;
			body.xRot = -0.5F * sit;
			head.y -= 0.5F * sit;
			tail.y += 4.8F * sit;
			tail.z += 1.4F * sit;
			rightHindLeg.y += 3.5F * sit;
			leftHindLeg.y += 3.5F * sit;
			rightHindLeg.z -= 1.0F * sit;
			leftHindLeg.z -= 1.0F * sit;
			rightHindLeg.xRot = Mth.lerp(sit, rightHindLeg.xRot, -Mth.HALF_PI);
			leftHindLeg.xRot = Mth.lerp(sit, leftHindLeg.xRot, -Mth.HALF_PI);
			rightFrontLeg.xRot = Mth.lerp(sit, rightFrontLeg.xRot, -0.08F);
			leftFrontLeg.xRot = Mth.lerp(sit, leftFrontLeg.xRot, -0.08F);
		}

		// --- Durmiendo: echado, patas estiradas, cabeza apoyada, ojos cerrados.
		if (sleep > 0.0F) {
			body.y += 5.0F * sleep;
			head.y += 10.0F * sleep;
			head.z -= 1.0F * sleep;
			head.xRot = Mth.lerp(sleep, head.xRot, 0.12F);
			head.yRot = Mth.lerp(sleep, head.yRot, 0.35F);
			head.zRot = Mth.lerp(sleep, head.zRot, 0.12F);
			tail.y += 10.5F * sleep;
			tail.yRot = 0.6F * sleep;
			for (ModelPart frontLeg : new ModelPart[] {rightFrontLeg, leftFrontLeg}) {
				frontLeg.y += 4.5F * sleep;
				frontLeg.xRot = Mth.lerp(sleep, frontLeg.xRot, -Mth.HALF_PI);
			}
			for (ModelPart hindLeg : new ModelPart[] {rightHindLeg, leftHindLeg}) {
				hindLeg.y += 4.5F * sleep;
				hindLeg.xRot = Mth.lerp(sleep, hindLeg.xRot, Mth.HALF_PI);
			}
			// Respiración lenta.
			body.y += Mth.sin(age * 0.08F) * 0.25F * sleep;
		}

		// --- Cola: más rápida y amplia cuanto más contento está.
		float happiness = s.mood / 100.0F;
		float wagSpeed = 0.25F + 0.95F * happiness + (s.excited ? 0.3F : 0.0F);
		float wagAmp = (0.25F + 0.55F * happiness) * (1.0F - 0.7F * sleep);
		tail.yRot += Mth.sin(age * wagSpeed) * wagAmp;
		tail.xRot = Mth.lerp(Math.min(1.0F, sit + sleep), 0.35F + 0.3F * happiness, sleep > sit ? 0.05F : -0.45F);

		// --- Orejas caídas que rebotan al trotar.
		float earFlop = 0.12F + 0.35F * Math.abs(Mth.sin(walkPos * swing)) * walkSpeed;
		leftEar.zRot = -earFlop - 0.15F * s.tiltProgress;
		rightEar.zRot = earFlop + 0.15F * s.tiltProgress;
		leftEar.xRot = -0.1F * walkSpeed;
		rightEar.xRot = -0.1F * walkSpeed;

		// --- Boca: habla, jadea o ladra.
		float mouth = 0.0F;
		if (s.panting) mouth = 0.28F + 0.06F * Mth.sin(age * 0.9F);
		if (s.speaking) mouth = Math.max(mouth, 0.35F * Math.abs(Mth.sin(age * 0.85F)));
		if (s.barkProgress > 0.0F) {
			float b = Mth.sin(s.barkProgress * Mth.PI);
			mouth = Math.max(mouth, 0.6F * b);
			head.xRot -= 0.25F * b;
		}
		jaw.xRot = mouth * (1.0F - sleep);
		tongue.visible = s.panting && sleep < 0.5F;

		// --- Ojos: parpadeo ocasional y cerrados al dormir (debajo hay párpados pintados).
		boolean blink = ((int) age + s.idHash) % 90 < 3;
		leftEye.visible = rightEye.visible = sleep < 0.5F && !blink;

		// --- Sacudida de perro mojado.
		if (s.shakeProgress > 0.0F) {
			float fade = 1.0F - s.shakeProgress;
			float roll = Mth.sin(s.shakeProgress * Mth.PI * 10.0F) * 0.35F * fade;
			body.zRot = roll;
			head.zRot += roll * 1.3F;
			tail.zRot = -roll;
		}
	}
}
