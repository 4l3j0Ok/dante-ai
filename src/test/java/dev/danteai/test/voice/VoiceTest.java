package dev.danteai.test.voice;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.danteai.test.MockHttpServer;
import dev.danteai.test.MockHttpServer.Reply;
import dev.danteai.voice.AudioUtil;
import dev.danteai.voice.OpenAiTtsProvider;
import dev.danteai.voice.PcmAudio;
import dev.danteai.voice.TranscriptionProvider;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoiceTest {
	private static short[] sine(int rate, double seconds, double freq) {
		short[] s = new short[(int) (rate * seconds)];
		for (int i = 0; i < s.length; i++) s[i] = (short) (Math.sin(2 * Math.PI * freq * i / rate) * 12000);
		return s;
	}

	/** WAV estéreo de 16 bits con un bloque LIST extra antes de "data" (como generan algunos servidores). */
	private static byte[] stereoWavWithExtraChunk(short[] mono, int rate) {
		ByteBuffer b = ByteBuffer.allocate(44 + 12 + mono.length * 4).order(ByteOrder.LITTLE_ENDIAN);
		b.putInt(0x46464952).putInt(0).putInt(0x45564157);
		b.putInt(0x20746d66).putInt(16).putShort((short) 1).putShort((short) 2).putInt(rate).putInt(rate * 4).putShort((short) 4).putShort((short) 16);
		b.putInt(0x5453494c).putInt(4).putInt(0); // "LIST"
		b.putInt(0x61746164).putInt(mono.length * 4);
		for (short s : mono) b.putShort(s).putShort(s);
		return b.array();
	}

	@Test
	void decodesWavVariants() {
		short[] tone = sine(24000, 0.5, 440);
		PcmAudio mono = AudioUtil.decodeWav(AudioUtil.encodeWav(tone, 24000));
		assertEquals(24000, mono.sampleRate());
		assertArrayEquals(tone, mono.samples());

		PcmAudio stereo = AudioUtil.decodeWav(stereoWavWithExtraChunk(tone, 22050));
		assertEquals(22050, stereo.sampleRate());
		assertArrayEquals(tone, stereo.samples(), "el estéreo se mezcla a mono");

		ByteBuffer f = ByteBuffer.allocate(44 + 8).order(ByteOrder.LITTLE_ENDIAN);
		f.putInt(0x46464952).putInt(0).putInt(0x45564157);
		f.putInt(0x20746d66).putInt(16).putShort((short) 3).putShort((short) 1).putInt(16000).putInt(64000).putShort((short) 4).putShort((short) 32);
		f.putInt(0x61746164).putInt(8).putFloat(0.5f).putFloat(-1.0f);
		PcmAudio floats = AudioUtil.decodeWav(f.array());
		assertEquals(16384, floats.samples()[0], 2);
		assertEquals(-32767, floats.samples()[1], 2);
	}

	@Test
	void producesSvcFramesAt48kWithPitch() {
		PcmAudio audio = new PcmAudio(sine(24000, 1.0, 300), 24000);
		List<short[]> normal = AudioUtil.toSvcFrames(audio, 1.0, 1.0);
		assertTrue(normal.stream().allMatch(fr -> fr.length == AudioUtil.SVC_FRAME_SIZE));
		assertEquals(50, normal.size(), 1, "1 segundo = 50 frames de 20 ms");
		List<short[]> higher = AudioUtil.toSvcFrames(audio, 1.25, 1.0);
		assertTrue(higher.size() < normal.size(), "más agudo = más corto");
		assertEquals(0, normal.getFirst()[0], "fundido de entrada sin clic");
	}

	@Test
	void gainClipsSafely() {
		short[] loud = {30000, -30000, 20000};
		short[] out = AudioUtil.applyGainAndFades(loud, 3.0, 48000);
		for (short s : out) assertTrue(s <= Short.MAX_VALUE && s >= Short.MIN_VALUE);
	}

	@Test
	void openAiTtsRequestAndDecoding() throws Exception {
		try (MockHttpServer server = new MockHttpServer()) {
			server.enqueue(Reply.bytes(AudioUtil.encodeWav(sine(24000, 0.2, 500), 24000)));
			OpenAiTtsProvider tts = new OpenAiTtsProvider(new OpenAiTtsProvider.Settings(server.baseUrl() + "/v1", "sk-1", "gpt-4o-mini-tts",
				"ash", "Hablá como un perro", 1.1, Duration.ofSeconds(5)));
			PcmAudio audio = tts.synthesize("¡Hola!").get(5, TimeUnit.SECONDS);
			assertEquals(24000, audio.sampleRate());
			assertEquals(4800, audio.samples().length);
			MockHttpServer.Recorded r = server.requests().getFirst();
			assertEquals("/v1/audio/speech", r.path());
			assertEquals("Bearer sk-1", r.header("Authorization"));
			JsonObject body = JsonParser.parseString(r.bodyText()).getAsJsonObject();
			assertEquals("¡Hola!", body.get("input").getAsString());
			assertEquals("ash", body.get("voice").getAsString());
			assertEquals("wav", body.get("response_format").getAsString());
			assertEquals("Hablá como un perro", body.get("instructions").getAsString());
		}
	}

	@Test
	void openAiTtsKnowsWhenConfiguredAndRejectsNonWavAudio() throws Exception {
		try (MockHttpServer server = new MockHttpServer()) {
			server.enqueue(Reply.bytes(new byte[] {1, 0, 2, 0, -1, -1}));
			OpenAiTtsProvider local = new OpenAiTtsProvider(new OpenAiTtsProvider.Settings(server.baseUrl(), "", "kokoro", "em_alex", "", 1.0,
				Duration.ofSeconds(5)));
			assertTrue(local.configured(), "un servidor local no necesita clave");
			var error = assertThrows(java.util.concurrent.ExecutionException.class, () -> local.synthesize("hola").get(5, TimeUnit.SECONDS));
			assertTrue(error.getCause() instanceof IllegalStateException, "audio que no es WAV debe fallar claro");
			assertFalse(JsonParser.parseString(server.requests().getFirst().bodyText()).getAsJsonObject().has("instructions"));
		}
		OpenAiTtsProvider cloud = new OpenAiTtsProvider(new OpenAiTtsProvider.Settings("https://api.openai.com/v1", "", "tts-1", "ash", "", 1.0,
			Duration.ofSeconds(5)));
		assertFalse(cloud.configured(), "la API oficial requiere clave");
	}

	@Test
	void transcriptionSendsMultipartWav() throws Exception {
		try (MockHttpServer server = new MockHttpServer()) {
			server.enqueue(Reply.json(200, "{\"text\": \" Dante, vení conmigo \"}"));
			TranscriptionProvider stt = new TranscriptionProvider(new TranscriptionProvider.Settings(server.baseUrl() + "/v1", "k", "whisper-1",
				"es", Duration.ofSeconds(5)));
			String text = stt.transcribe(sine(48000, 0.5, 200), 48000).get(5, TimeUnit.SECONDS);
			assertEquals("Dante, vení conmigo", text);
			MockHttpServer.Recorded r = server.requests().getFirst();
			assertEquals("/v1/audio/transcriptions", r.path());
			assertTrue(r.header("Content-Type").startsWith("multipart/form-data; boundary="));
			String body = new String(r.body(), StandardCharsets.ISO_8859_1);
			assertTrue(body.contains("name=\"model\"\r\n\r\nwhisper-1"));
			assertTrue(body.contains("name=\"language\"\r\n\r\nes"));
			assertTrue(body.contains("filename=\"voice.wav\""));
			assertTrue(body.contains("RIFF"));
		}
	}
}
