package dev.danteai.gametest;

import dev.danteai.DanteAi;
import dev.danteai.entity.DanteEntity;
import dev.danteai.entity.ModEntities;
import dev.danteai.world.DanteManager;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Prueba de cliente real: renderiza el modelo de Dante en distintas poses y toma capturas
 * (build/run/clientGameTest/screenshots), y verifica la sincronización con un servidor dedicado.
 */
@SuppressWarnings("UnstableApiUsage")
public class DanteClientGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		// Simple Voice Chat owns native microphone threads. Avoid switching between
		// integrated and dedicated servers within the same voice-enabled test process.
		if (!net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("voicechat")) {
			singleplayerPoses(context);
		}
		dedicatedServer(context);
	}

	private static void placeDante(TestServerContext server, double distance, float danteYawOffset, int pose, boolean listening, boolean panting) {
		server.runOnServer(s -> {
			ServerPlayer player = s.getPlayerList().getPlayers().getFirst();
			DanteManager manager = DanteAi.manager();
			DanteEntity dante = manager.body();
			if (dante == null) dante = manager.spawnNear(player.level(), player.blockPosition());
			Vec3 look = Vec3.directionFromRotation(0, player.getYRot());
			Vec3 pos = player.position().add(look.scale(distance));
			float yaw = player.getYRot() + 180 + danteYawOffset;
			dante.setNoAi(true);
			dante.snapTo(pos.x, player.getY(), pos.z, yaw, 0);
			dante.setYHeadRot(yaw);
			dante.yBodyRot = yaw;
			dante.setDantePose(pose);
			dante.setMood(100);
			dante.setFlag(DanteEntity.FLAG_PANTING, panting);
			if (listening) dante.startListening(400);
			else dante.stopListening();
		});
	}

	private static void singleplayerPoses(ClientGameTestContext context) {
		try (TestSingleplayerContext sp = context.worldBuilder().create()) {
			sp.getServer().runCommand("time set noon");
			sp.getServer().runCommand("weather clear");
			sp.getServer().runCommand("gamerule advance_time false");
			sp.getConnection().waitForChunksRender();

			// Sin jugadores lejos, Dante aparece solo cerca del jugador (auto_spawn).
			sp.getServer().waitFor(s -> DanteAi.manager() != null && DanteAi.manager().body() != null, 20 * 15);
			context.getInput().lookAt(0, 20);

			placeDante(sp.getServer(), 3.2, -35, DanteEntity.POSE_STANDING, false, true);
			context.waitTicks(25);
			context.takeScreenshot("dante-standing");

			placeDante(sp.getServer(), 3.2, 0, DanteEntity.POSE_STANDING, true, false);
			context.waitTicks(15);
			context.takeScreenshot("dante-listening-front");

			placeDante(sp.getServer(), 3.2, 90, DanteEntity.POSE_STANDING, false, false);
			context.waitTicks(10);
			context.takeScreenshot("dante-side");

			placeDante(sp.getServer(), 3.2, -30, DanteEntity.POSE_SITTING, false, false);
			context.waitTicks(25);
			context.takeScreenshot("dante-sitting");

			placeDante(sp.getServer(), 3.2, -60, DanteEntity.POSE_SLEEPING, false, false);
			context.waitTicks(40);
			context.takeScreenshot("dante-sleeping");

			sp.getServer().runCommand("dante say ¡Guau! Soy Dante, ¿jugamos?");
			context.waitTicks(10);
			context.takeScreenshot("dante-chat");
		}
	}

	private static void dedicatedServer(ClientGameTestContext context) {
		try (TestDedicatedServerContext server = context.worldBuilder().createServer();
				TestDedicatedServerConnection connection = server.connect()) {
			connection.waitForChunksRender();
			// En el servidor dedicado Dante aparece solo cerca del primer jugador y el cliente lo recibe.
			server.waitFor(s -> DanteAi.manager() != null && DanteAi.manager().body() != null, 20 * 20);
			if (Boolean.getBoolean("danteai.gametest.localLlm")) {
				java.util.concurrent.CompletableFuture<String> response = server.computeOnServer(s ->
					DanteAi.manager().llm().simple("Respond exactly with the marker requested by the user.", "DANTE_IN_GAME_LOCAL_LLM_OK", 160));
				context.waitFor(mc -> response.isDone(), 20 * 90);
				if (!response.isDone()) throw new AssertionError("El LLM local no respondió desde el servidor Minecraft en 90 segundos");
				String text;
				try {
					text = response.join();
				} catch (java.util.concurrent.CompletionException e) {
					throw new AssertionError("Falló el LLM local desde el servidor Minecraft", e.getCause());
				}
				if (!text.contains("DANTE_IN_GAME_LOCAL_LLM_OK")) throw new AssertionError("Respuesta inesperada del LLM local: " + text);
			}
			int waited = context.waitFor(mc -> countDante(mc) == 1, 20 * 10);
			if (countDante(context.computeOnClient(mc -> mc)) != 1) {
				throw new AssertionError("El cliente debería ver exactamente un Dante");
			}
			String name = context.computeOnClient(mc -> {
				for (Entity e : mc.level.entitiesForRendering()) {
					if (e.getType() == ModEntities.DANTE) return e.getCustomName() == null ? "" : e.getCustomName().getString();
				}
				return "";
			});
			if (!name.equals("Dante")) throw new AssertionError("Nombre sobre la cabeza: '" + name + "'");
			// Un segundo /summon no crea otro Dante: el duplicado se descarta.
			server.runCommand("summon danteai:dante ~ ~ ~");
			context.waitTicks(20);
			int count = server.computeOnServer(DanteClientGameTest::countDanteOnServer);
			if (count != 1) throw new AssertionError("Debería haber un solo Dante en el servidor, hay " + count);
			placeDante(server, 3.0, -35, DanteEntity.POSE_STANDING, false, false);
			context.waitTicks(20);
			context.takeScreenshot("dante-dedicated-server");
			if (waited < 0) throw new AssertionError("timeout");
			voiceThroughSimpleVoiceChat(context, server);
		}
	}

	/**
	 * Voz de punta a punta: un servidor TTS local (compatible con /v1/audio/speech) devuelve un WAV, Dante AI lo
	 * convierte y Simple Voice Chat lo transmite por el canal de audio de la entidad de Dante.
	 */
	private static void voiceThroughSimpleVoiceChat(ClientGameTestContext context, TestDedicatedServerContext server) {
		if (Boolean.getBoolean("danteai.gametest.kokoro")) {
			String userText = "Dante, ¿cómo estás?";
			if (Boolean.getBoolean("danteai.gametest.speaches")) {
				userText = transcribeWithSpeaches("Dante, ¿cómo estás?");
				if (!userText.toLowerCase(java.util.Locale.ROOT).contains("dante")) {
					throw new AssertionError("Speaches no reconoció la mención de Dante: " + userText);
				}
			}
			final String prompt = userText;
			int ready = server.waitFor(s -> dev.danteai.voice.VoiceBridge.Holder.get() != null, 20 * 20);
			if (ready < 0) throw new AssertionError("El servidor de voz de Simple Voice Chat no arrancó");
			java.util.concurrent.CompletableFuture<String> llmResponse = server.computeOnServer(s -> {
				DanteManager manager = DanteAi.manager();
				var voice = DanteAi.config().voice;
				voice.enabled = true;
				voice.openai.base_url = "http://127.0.0.1:28880/v1";
				voice.openai.api_key = "";
				voice.openai.model = "kokoro";
				voice.openai.voice = "em_alex";
				voice.openai.instructions = "";
				manager.voice().configure(voice);
				return manager.llm().simple("Respondé de forma breve y natural a la pregunta del jugador.",
					prompt, 160);
			});
			server.waitFor(s -> llmResponse.isDone(), 20 * 90);
			if (!llmResponse.isDone()) throw new AssertionError("El LLM no respondió antes de sintetizar con Kokoro");
			String text;
			try {
				text = llmResponse.join();
			} catch (java.util.concurrent.CompletionException e) {
				throw new AssertionError("Falló la respuesta del LLM local", e.getCause());
			}
			if (text.isBlank()) throw new AssertionError("El LLM devolvió texto vacío para Kokoro");
			java.util.concurrent.CompletableFuture<Boolean> spoken = server.computeOnServer(s -> DanteAi.manager().voice().speak(text));
			server.waitFor(s -> spoken.isDone(), 20 * 90);
			if (!Boolean.TRUE.equals(spoken.getNow(false))) throw new AssertionError("Kokoro no reprodujo la respuesta del LLM por Simple Voice Chat");
			context.waitTicks(30);
			context.takeScreenshot("dante-kokoro-voice");
			return;
		}
		com.sun.net.httpserver.HttpServer tts;
		java.util.concurrent.atomic.AtomicInteger requests = new java.util.concurrent.atomic.AtomicInteger();
		try {
			tts = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
		} catch (java.io.IOException e) {
			throw new AssertionError(e);
		}
		short[] tone = new short[24000];
		for (int i = 0; i < tone.length; i++) tone[i] = (short) (Math.sin(2 * Math.PI * 330 * i / 24000.0) * 8000);
		byte[] wav = dev.danteai.voice.AudioUtil.encodeWav(tone, 24000);
		tts.createContext("/v1/audio/speech", exchange -> {
			requests.incrementAndGet();
			exchange.getRequestBody().readAllBytes();
			exchange.sendResponseHeaders(200, wav.length);
			exchange.getResponseBody().write(wav);
			exchange.close();
		});
		tts.start();
		try {
			String url = "http://127.0.0.1:" + tts.getAddress().getPort() + "/v1";
			int ready = server.waitFor(s -> dev.danteai.voice.VoiceBridge.Holder.get() != null, 20 * 20);
			if (ready < 0) throw new AssertionError("El servidor de voz de Simple Voice Chat no arrancó");
			Boolean spoke = server.computeOnServer(s -> {
				DanteManager manager = DanteAi.manager();
				var voice = DanteAi.config().voice;
				voice.enabled = true;
				voice.openai.base_url = url;
				voice.openai.api_key = "";
				manager.voice().configure(voice);
				return manager.voice().active();
			});
			if (!spoke) throw new AssertionError("La voz debería estar activa: " + server.computeOnServer(s -> DanteAi.manager().voice().status()));
			java.util.concurrent.CompletableFuture<Boolean> result = server.computeOnServer(s -> DanteAi.manager().voice().speak("¡Guau! ¿Me escuchan?"));
			server.waitFor(s -> result.isDone(), 20 * 15);
			if (!Boolean.TRUE.equals(result.getNow(false))) throw new AssertionError("Simple Voice Chat no reprodujo la voz de Dante");
			if (requests.get() != 1) throw new AssertionError("Se esperaba 1 solicitud TTS, hubo " + requests.get());
			context.waitTicks(30);
			context.takeScreenshot("dante-speaking-voice");
		} finally {
			tts.stop(0);
		}
	}

	private static String transcribeWithSpeaches(String text) {
		try {
			var client = java.net.http.HttpClient.newHttpClient();
			String json = "{\"model\":\"kokoro\",\"input\":" + new com.google.gson.Gson().toJson(text)
				+ ",\"voice\":\"em_alex\",\"response_format\":\"wav\"}";
			var ttsRequest = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:28880/v1/audio/speech"))
				.header("Content-Type", "application/json")
				.POST(java.net.http.HttpRequest.BodyPublishers.ofString(json)).build();
			var ttsResponse = client.send(ttsRequest, java.net.http.HttpResponse.BodyHandlers.ofByteArray());
			if (ttsResponse.statusCode() != 200) throw new AssertionError("Kokoro respondió HTTP " + ttsResponse.statusCode());
			var pcm = dev.danteai.voice.AudioUtil.decodeWav(ttsResponse.body());
			var stt = new dev.danteai.voice.TranscriptionProvider(new dev.danteai.voice.TranscriptionProvider.Settings(
				"http://127.0.0.1:28000/v1", "", "Systran/faster-whisper-small", "es", java.time.Duration.ofSeconds(120)));
			return stt.transcribe(pcm.samples(), pcm.sampleRate()).get(130, java.util.concurrent.TimeUnit.SECONDS);
		} catch (Exception e) {
			throw new AssertionError("Falló la transcripción local con Speaches", e);
		}
	}

	private static int countDante(Minecraft mc) {
		if (mc.level == null) return 0;
		int n = 0;
		for (Entity e : mc.level.entitiesForRendering()) {
			if (e.getType() == ModEntities.DANTE && !e.isRemoved()) n++;
		}
		return n;
	}

	private static int countDanteOnServer(MinecraftServer s) {
		int n = 0;
		for (var level : s.getAllLevels()) {
			for (Entity e : level.getAllEntities()) {
				if (e.getType() == ModEntities.DANTE && !e.isRemoved()) n++;
			}
		}
		return n;
	}
}
