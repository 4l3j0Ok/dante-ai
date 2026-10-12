package dev.danteai.client;

/** Datos de un frame para animar a Dante (copiados de la entidad sincronizada). */
public class DanteRenderState {
	public float ageInTicks;
	public float walkAnimationPos;
	public float walkAnimationSpeed;
	public float yRot;
	public float xRot;
	public float sitProgress;
	public float sleepProgress;
	public float tiltProgress;
	public float shakeProgress;
	public float barkProgress;
	public int mood = 70;
	public boolean speaking;
	public boolean panting;
	public boolean excited;
	public int idHash;
}
