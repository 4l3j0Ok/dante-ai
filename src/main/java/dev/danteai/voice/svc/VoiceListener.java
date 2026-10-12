package dev.danteai.voice.svc;

import de.maxhenkel.voicechat.api.Position;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import dev.danteai.DanteAi;
import dev.danteai.config.DanteConfig;
import dev.danteai.util.DanteLog;
import dev.danteai.voice.AudioUtil;
import dev.danteai.voice.TranscriptionProvider;
import dev.danteai.world.DanteManager;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Escucha opcional: junta el audio de micrófono de los jugadores cercanos a Dante, detecta el fin de cada frase por
 * silencio y la transcribe con un servicio speech-to-text. El texto entra a la conversación como si fuera chat.
 * Nunca toca objetos de Minecraft fuera del hilo del servidor: usa la posición publicada por el administrador.
 */
final class VoiceListener {
	private static final class Utterance {
		final OpusDecoder decoder;
		final List<short[]> chunks = new ArrayList<>();
		long lastPacket;
		long started;
		int samples;

		Utterance(OpusDecoder decoder) {
			this.decoder = decoder;
		}
	}

	private final VoicechatServerApi api;
	private final Map<UUID, Utterance> utterances = new ConcurrentHashMap<>();
	private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "danteai-voice-listener");
		t.setDaemon(true);
		return t;
	});

	VoiceListener(VoicechatServerApi api) {
		this.api = api;
		timer.scheduleAtFixedRate(this::flushFinished, 200, 200, TimeUnit.MILLISECONDS);
	}

	void onMicrophone(MicrophonePacketEvent event) {
		DanteManager manager = DanteAi.manager();
		if (manager == null) return;
		DanteConfig.Listen cfg = manager.config().voice.listen;
		if (!manager.config().voice.enabled || !cfg.enabled) return;
		VoicechatConnection sender = event.getSenderConnection();
		DanteManager.Position dante = manager.position();
		if (sender == null || dante == null) return;
		if (sender.getPlayer().getServerLevel().getServerLevel() != dante.level()) return;
		Position p = sender.getPlayer().getPosition();
		double dx = p.getX() - dante.x();
		double dy = p.getY() - dante.y();
		double dz = p.getZ() - dante.z();
		if (dx * dx + dy * dy + dz * dz > cfg.distance * cfg.distance) return;
		byte[] opus = event.getPacket().getOpusEncodedData();
		if (opus == null || opus.length == 0) return;
		UUID id = sender.getPlayer().getUuid();
		Utterance u = utterances.computeIfAbsent(id, k -> new Utterance(api.createDecoder()));
		synchronized (u) {
			short[] pcm = u.decoder.decode(opus);
			long now = System.currentTimeMillis();
			if (u.chunks.isEmpty()) u.started = now;
			u.lastPacket = now;
			if (u.samples < cfg.max_seconds * AudioUtil.SVC_SAMPLE_RATE) {
				u.chunks.add(pcm);
				u.samples += pcm.length;
			}
		}
	}

	private void flushFinished() {
		try {
			DanteManager manager = DanteAi.manager();
			if (manager == null) return;
			DanteConfig.Listen cfg = manager.config().voice.listen;
			long now = System.currentTimeMillis();
			for (Map.Entry<UUID, Utterance> e : utterances.entrySet()) {
				Utterance u = e.getValue();
				short[] audio;
				synchronized (u) {
					if (u.chunks.isEmpty()) continue;
					boolean silence = now - u.lastPacket >= cfg.silence_ms;
					boolean tooLong = now - u.started >= cfg.max_seconds * 1000L;
					if (!silence && !tooLong) continue;
					audio = AudioUtil.concat(u.chunks);
					u.chunks.clear();
					u.samples = 0;
					u.decoder.resetState();
				}
				if (audio.length < cfg.min_ms * AudioUtil.SVC_SAMPLE_RATE / 1000 || AudioUtil.rms(audio) < 0.01) continue;
				transcribe(manager, e.getKey(), audio, cfg);
			}
		} catch (RuntimeException ex) {
			DanteLog.error("Error procesando audio de micrófono", ex);
		}
	}

	private void transcribe(DanteManager manager, UUID player, short[] audio, DanteConfig.Listen cfg) {
		TranscriptionProvider stt = new TranscriptionProvider(new TranscriptionProvider.Settings(cfg.base_url, cfg.api_key, cfg.model,
			cfg.language, Duration.ofSeconds(manager.config().voice.timeout_seconds)));
		if (!stt.configured()) return;
		stt.transcribe(audio, AudioUtil.SVC_SAMPLE_RATE).whenComplete((text, error) -> {
			if (error != null) {
				DanteLog.warn("No se pudo transcribir la voz: {}", error.getMessage());
				return;
			}
			DanteLog.debug("Transcripción de {}: {}", player, text);
			manager.server().execute(() -> {
				DanteManager current = DanteAi.manager();
				if (current != null) current.conversation().onVoice(player, text);
			});
		});
	}

	void forget(UUID player) {
		Utterance u = utterances.remove(player);
		if (u != null) u.decoder.close();
	}

	void shutdown() {
		timer.shutdownNow();
		utterances.values().forEach(u -> u.decoder.close());
		utterances.clear();
	}
}
