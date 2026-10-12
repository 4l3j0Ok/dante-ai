package dev.danteai.voice;

import dev.danteai.chat.TextUtil;
import dev.danteai.config.DanteConfig;
import dev.danteai.entity.DanteEntity;
import dev.danteai.llm.RequestScheduler;
import dev.danteai.util.DanteLog;
import net.minecraft.server.MinecraftServer;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Convierte lo que dice Dante en voz: sintetiza con el proveedor TTS configurado (fuera del hilo del servidor) y
 * reproduce el audio desde la posición de Dante a través de Simple Voice Chat.
 */
public final class VoiceService {
	private static final int MAX_QUEUED = 3;
	private final MinecraftServer server;
	private final Supplier<DanteEntity> body;
	private final VoiceQueue queue;
	private volatile DanteConfig.Voice config;
	private volatile TtsProvider provider;
	private volatile RequestScheduler scheduler;
	private volatile boolean warnedUnconfigured;
	private volatile long failuresUntil;

	public VoiceService(MinecraftServer server, Supplier<DanteEntity> body, DanteConfig.Voice config) {
		this.server = server;
		this.body = body;
		this.queue = new VoiceQueue(MAX_QUEUED, server, this::startPlayback);
		configure(config);
	}

	public void configure(DanteConfig.Voice cfg) {
		RequestScheduler old = scheduler;
		if (old != null) old.cancelAll();
		if (old != null) queue.clear();
		this.config = cfg;
		this.provider = createProvider(cfg);
		this.scheduler = new RequestScheduler(cfg.max_concurrent, 3, 40);
		this.warnedUnconfigured = false;
		this.failuresUntil = 0;
	}

	public static TtsProvider createProvider(DanteConfig.Voice cfg) {
		Duration timeout = Duration.ofSeconds(cfg.timeout_seconds);
		DanteConfig.OpenAiTts o = cfg.openai;
		return new OpenAiTtsProvider(new OpenAiTtsProvider.Settings(o.base_url, o.api_key, o.model, o.voice, o.instructions, o.speed, timeout));
	}

	/** Estado legible para /dante status. */
	public String status() {
		VoiceBridge bridge = VoiceBridge.Holder.get();
		if (!config.enabled) return "desactivada en la configuración";
		if (bridge == null) return "Simple Voice Chat no está instalado o su servidor de voz no arrancó";
		if (!bridge.ready()) return "esperando al servidor de voz";
		if (!provider.configured()) return "falta configurar el proveedor TTS (" + provider.name() + ")";
		return "activa (" + provider.name() + ")";
	}

	public boolean active() {
		VoiceBridge bridge = VoiceBridge.Holder.get();
		return config.enabled && bridge != null && bridge.ready() && provider.configured();
	}

	/** Dice el texto en voz alta si la voz está disponible. No bloquea; los errores solo se registran. */
	public CompletableFuture<Boolean> speak(String text) {
		DanteConfig.Voice cfg = config;
		VoiceBridge bridge = VoiceBridge.Holder.get();
		if (!cfg.enabled || bridge == null || !bridge.ready()) return CompletableFuture.completedFuture(false);
		if (!provider.configured()) {
			if (!warnedUnconfigured) {
				warnedUnconfigured = true;
				DanteLog.warn("Simple Voice Chat está disponible pero la voz de Dante no tiene proveedor TTS configurado (voice.*).");
			}
			return CompletableFuture.completedFuture(false);
		}
		if (System.currentTimeMillis() < failuresUntil) return CompletableFuture.completedFuture(false);
		String speakable = TextUtil.truncateForSpeech(TextUtil.speakable(text), cfg.max_characters);
		if (speakable.isBlank()) return CompletableFuture.completedFuture(false);
		TtsProvider tts = provider;
		VoiceQueue.Ticket ticket = queue.enqueue();
		scheduler.submit(() -> tts.synthesize(speakable)).whenComplete((audio, error) -> {
			if (error != null) {
				// Si el servicio falla, se pausa la voz un minuto para no insistir en cada mensaje.
				failuresUntil = System.currentTimeMillis() + 60_000;
				DanteLog.warn("No se pudo sintetizar la voz de Dante: {}", error.getMessage());
				queue.failed(ticket);
				return;
			}
			try {
				queue.ready(ticket, AudioUtil.toSvcFrames(audio, cfg.pitch, cfg.gain));
			} catch (RuntimeException e) {
				DanteLog.warn("No se pudo procesar el audio de Dante: {}", e.getMessage());
				queue.failed(ticket);
			}
		});
		return ticket.result();
	}

	/** Reproduce un audio ya sintetizado desde la posición de Dante (hilo del servidor). */
	private CompletableFuture<Boolean> startPlayback(List<short[]> frames) {
		DanteEntity speaker = body.get();
		VoiceBridge current = VoiceBridge.Holder.get();
		if (speaker == null || current == null || !current.ready()) return null;
		CompletableFuture<Boolean> finished = current.play(speaker, frames, config.distance);
		speaker.startSpeaking(frames.size() / 50 * 20 + 10);
		return finished;
	}

	/** Corta lo que está diciendo y descarta los mensajes pendientes. */
	public void silence() {
		queue.clear();
		VoiceBridge bridge = VoiceBridge.Holder.get();
		if (bridge != null) bridge.stopAll();
	}

	public void shutdown() {
		RequestScheduler s = scheduler;
		if (s != null) s.cancelAll();
		silence();
	}
}
