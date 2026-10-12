package dev.danteai.behavior;

/**
 * Necesidades internas que hacen variar el comportamiento: energía (baja al moverse, sube al descansar),
 * necesidad social (sube estando solo) y curiosidad (sube con el tiempo, baja al explorar). Todas en [0, 1].
 */
public final class Needs {
	private double energy = 0.8;
	private double social = 0.5;
	private double curiosity = 0.5;

	public double energy() {
		return energy;
	}

	public double social() {
		return social;
	}

	public double curiosity() {
		return curiosity;
	}

	public void set(double energy, double social, double curiosity) {
		this.energy = clamp(energy);
		this.social = clamp(social);
		this.curiosity = clamp(curiosity);
	}

	/**
	 * Avanza las necesidades un segundo de juego.
	 *
	 * @param activity    actividad actual
	 * @param moving      si se está desplazando
	 * @param withPlayers si hay jugadores cerca
	 */
	public void tickSecond(Activity activity, boolean moving, boolean withPlayers) {
		if (activity == Activity.RESTING) {
			energy += 0.012;
		} else if (moving) {
			energy -= activity == Activity.FOLLOWING || activity == Activity.FLEEING ? 0.0035 : 0.0025;
		} else {
			energy += 0.002;
		}
		social += withPlayers ? -0.006 : 0.003;
		if (activity == Activity.SOCIALIZING || activity == Activity.FOLLOWING) social -= 0.006;
		curiosity += activity == Activity.EXPLORING ? -0.02 : 0.0025;
		energy = clamp(energy);
		social = clamp(social);
		curiosity = clamp(curiosity);
	}

	private static double clamp(double v) {
		return Math.max(0, Math.min(1, v));
	}
}
