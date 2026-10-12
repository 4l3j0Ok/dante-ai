package dev.danteai.voice;

import java.util.concurrent.CompletableFuture;

/** Proveedor de texto a voz. Asincrónico; nunca se llama desde el hilo del servidor de forma bloqueante. */
public interface TtsProvider {
	String name();

	/** {@code false} si falta configuración imprescindible (por ejemplo la API key de un servicio en la nube). */
	boolean configured();

	CompletableFuture<PcmAudio> synthesize(String text);
}
