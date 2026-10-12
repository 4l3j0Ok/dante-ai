package dev.danteai.behavior;

/** Estados de alto nivel de la máquina de estados de Dante. */
public enum Activity {
	/** Pasea libremente por los alrededores. */
	WANDERING,
	/** Sigue a un jugador durante un tiempo variable. */
	FOLLOWING,
	/** Se sienta o duerme. */
	RESTING,
	/** Se acerca, observa e interactúa con jugadores. */
	SOCIALIZING,
	/** Investiga bloques o lugares cercanos. */
	EXPLORING,
	/** Aparece cerca de un jugador. */
	TELEPORTING,
	/** Ejecuta una acción pedida por un jugador (entregar, recoger, cofres...). */
	INTERACTING,
	/** Se aleja de un peligro (fuego, lava, explosiones, daño). */
	FLEEING,
	/** Se queda parado mirando alrededor. */
	IDLE;

	public String displayName() {
		return switch (this) {
			case WANDERING -> "paseando";
			case FOLLOWING -> "siguiendo a alguien";
			case RESTING -> "descansando";
			case SOCIALIZING -> "socializando";
			case EXPLORING -> "explorando";
			case TELEPORTING -> "yendo a buscar a alguien";
			case INTERACTING -> "haciendo un encargo";
			case FLEEING -> "escapando de un peligro";
			case IDLE -> "mirando alrededor";
		};
	}
}
