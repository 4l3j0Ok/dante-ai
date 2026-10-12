package dev.danteai.voice.svc;

import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.VolumeCategory;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.PlayerDisconnectedEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStoppedEvent;
import dev.danteai.DanteAi;
import dev.danteai.util.DanteLog;
import dev.danteai.voice.VoiceBridge;

/**
 * Plugin de Simple Voice Chat. Solo se carga si ese mod está instalado (entrypoint {@code voicechat} en
 * {@code fabric.mod.json}); ninguna otra clase de Dante AI referencia su API.
 */
public final class DanteVoicechatPlugin implements VoicechatPlugin {
	public static final String CATEGORY = "danteai";

	private SvcVoiceBridge bridge;
	private VoiceListener listener;

	@Override
	public String getPluginId() {
		return DanteAi.MOD_ID;
	}

	@Override
	public void initialize(VoicechatApi api) {
		DanteLog.info("Simple Voice Chat detectado: Dante puede hablar con voz.");
	}

	@Override
	public void registerEvents(EventRegistration registration) {
		registration.registerEvent(VoicechatServerStartedEvent.class, this::onServerStarted);
		registration.registerEvent(VoicechatServerStoppedEvent.class, event -> onServerStopped());
		registration.registerEvent(MicrophonePacketEvent.class, event -> {
			VoiceListener l = listener;
			if (l != null) l.onMicrophone(event);
		});
		registration.registerEvent(PlayerDisconnectedEvent.class, event -> {
			VoiceListener l = listener;
			if (l != null) l.forget(event.getPlayerUuid());
		});
	}

	private void onServerStarted(VoicechatServerStartedEvent event) {
		VoicechatServerApi api = event.getVoicechat();
		VolumeCategory category = api.volumeCategoryBuilder()
			.setId(CATEGORY)
			.setName("Dante")
			.setDescription("Voz de Dante (Dante AI)")
			.setIcon(DanteIcon.create())
			.build();
		api.registerVolumeCategory(category);
		bridge = new SvcVoiceBridge(api);
		listener = new VoiceListener(api);
		VoiceBridge.Holder.set(bridge);
		DanteLog.info("Servidor de voz listo: la voz de Dante se transmite por Simple Voice Chat.");
	}

	private void onServerStopped() {
		VoiceBridge.Holder.set(null);
		if (bridge != null) bridge.stopAll();
		if (listener != null) listener.shutdown();
		bridge = null;
		listener = null;
	}
}
