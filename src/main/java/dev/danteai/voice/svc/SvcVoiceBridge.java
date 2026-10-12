package dev.danteai.voice.svc;

import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.AudioPlayer;
import de.maxhenkel.voicechat.api.audiochannel.EntityAudioChannel;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import dev.danteai.util.DanteLog;
import dev.danteai.voice.VoiceBridge;
import net.minecraft.world.entity.Entity;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Reproduce la voz de Dante en un canal de entidad: los jugadores con Simple Voice Chat la escuchan en 3D,
 * saliendo de Dante, con la atenuación por distancia del propio mod. La cola de mensajes vive en
 * {@code VoiceService}, que espera a que termine cada audio antes de pedir el siguiente.
 */
final class SvcVoiceBridge implements VoiceBridge {
	private final VoicechatServerApi api;
	private AudioPlayer current;

	SvcVoiceBridge(VoicechatServerApi api) {
		this.api = api;
	}

	@Override
	public boolean ready() {
		return true;
	}

	@Override
	public synchronized CompletableFuture<Boolean> play(Entity speaker, List<short[]> frames, float distance) {
		EntityAudioChannel channel = api.createEntityAudioChannel(UUID.randomUUID(), api.fromEntity(speaker));
		if (channel == null) {
			DanteLog.warn("Simple Voice Chat no pudo crear el canal de audio para Dante");
			return CompletableFuture.completedFuture(false);
		}
		channel.setCategory(DanteVoicechatPlugin.CATEGORY);
		channel.setDistance(distance);
		OpusEncoder encoder = api.createEncoder();
		AtomicInteger index = new AtomicInteger();
		AudioPlayer player = api.createAudioPlayer(channel, encoder, () -> {
			int i = index.getAndIncrement();
			return i < frames.size() ? frames.get(i) : null;
		});
		CompletableFuture<Boolean> done = new CompletableFuture<>();
		player.setOnStopped(() -> {
			if (!encoder.isClosed()) encoder.close();
			done.complete(true);
		});
		current = player;
		player.startPlaying();
		return done;
	}

	@Override
	public synchronized void stopAll() {
		if (current != null && !current.isStopped()) current.stopPlaying();
		current = null;
	}
}
