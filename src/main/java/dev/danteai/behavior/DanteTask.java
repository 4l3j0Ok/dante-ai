package dev.danteai.behavior;

import dev.danteai.entity.DanteEntity;
import dev.danteai.world.DanteManager;
import net.minecraft.server.level.ServerLevel;

/** Encargo con duración (estado INTERACTING): caminar hacia algo y completar una acción real al llegar. */
public interface DanteTask {
	enum Status { RUNNING, SUCCESS, FAILURE }

	String describe();

	Status tick(DanteEntity body, ServerLevel level, DanteManager manager);

	/** Se llama si el encargo se cancela o vence. Nunca debe dejar objetos "en el aire". */
	default void onCancel(DanteManager manager, String reason) {
	}

	default int timeoutTicks() {
		return 20 * 60;
	}
}
