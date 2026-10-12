package dev.danteai.voice;

import net.minecraft.world.entity.Entity;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Salida de audio posicional. La implementa la integración con Simple Voice Chat; este contrato no usa clases de
 * ese mod para que Dante AI funcione igual cuando no está instalado.
 */
public interface VoiceBridge {
	/** {@code true} cuando el servidor de voz está activo. */
	boolean ready();

	/**
	 * Reproduce audio que sale de la entidad (se escucha en 3D y la sigue si se mueve). Debe llamarse en el hilo
	 * del servidor; la reproducción continúa en un hilo propio del mod de voz.
	 *
	 * @param frames frames PCM de 960 muestras a 48 kHz
	 * @return futuro que se completa con {@code true} cuando el audio terminó o fue detenido, o con {@code false}
	 *         si no pudo empezar. No corta el audio anterior: quien llama es responsable de esperar.
	 */
	CompletableFuture<Boolean> play(Entity speaker, List<short[]> frames, float distance);

	void stopAll();

	/** Puente estático: lo registra el plugin de Simple Voice Chat cuando arranca su servidor. */
	final class Holder {
		private static volatile VoiceBridge bridge;

		private Holder() {
		}

		public static VoiceBridge get() {
			return bridge;
		}

		public static void set(VoiceBridge value) {
			bridge = value;
		}
	}
}
