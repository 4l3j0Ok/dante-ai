package dev.danteai.voice;

import com.google.gson.JsonObject;
import dev.danteai.llm.HttpSupport;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Texto a voz con la API {@code POST {base}/audio/speech} de OpenAI y servidores compatibles (Kokoro-FastAPI,
 * LocalAI, etc.). Siempre pide WAV.
 */
public final class OpenAiTtsProvider implements TtsProvider {
	public record Settings(String baseUrl, String apiKey, String model, String voice, String instructions, double speed,
			Duration timeout) {
	}

	private final Settings settings;

	public OpenAiTtsProvider(Settings settings) {
		this.settings = settings;
	}

	@Override
	public String name() {
		return "openai-tts (" + settings.model + "/" + settings.voice + ")";
	}

	@Override
	public boolean configured() {
		boolean officialApi = settings.baseUrl.contains("api.openai.com");
		return !officialApi || !settings.apiKey.isBlank();
	}

	JsonObject buildBody(String text) {
		JsonObject body = new JsonObject();
		body.addProperty("model", settings.model);
		body.addProperty("input", text);
		body.addProperty("voice", settings.voice);
		body.addProperty("response_format", "wav");
		body.addProperty("speed", settings.speed);
		if (settings.instructions != null && !settings.instructions.isBlank()) {
			body.addProperty("instructions", settings.instructions);
		}
		return body;
	}

	@Override
	public CompletableFuture<PcmAudio> synthesize(String text) {
		URI uri = URI.create(stripSlash(settings.baseUrl) + "/audio/speech");
		Map<String, String> headers = new LinkedHashMap<>();
		headers.put("Content-Type", "application/json");
		if (!settings.apiKey.isBlank()) headers.put("Authorization", "Bearer " + settings.apiKey.strip());
		String json = buildBody(text).toString();
		return HttpSupport.withRetries(() -> HttpSupport.post(uri, headers, HttpRequest.BodyPublishers.ofString(json), settings.timeout), 1, 700)
			.thenApply(this::decode);
	}

	PcmAudio decode(byte[] bytes) {
		boolean isWav = bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F';
		if (isWav) return AudioUtil.decodeWav(bytes);
		if (bytes.length > 0 && bytes[0] == '{') {
			throw new IllegalStateException("El servidor TTS devolvió JSON en lugar de audio: " + HttpSupport.abbreviate(new String(bytes), 200));
		}
		throw new IllegalStateException("El servidor TTS no devolvió audio WAV (" + bytes.length + " bytes)");
	}

	static String stripSlash(String url) {
		String u = url.strip();
		while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
		return u;
	}
}
