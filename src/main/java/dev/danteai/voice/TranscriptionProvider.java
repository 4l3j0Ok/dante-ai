package dev.danteai.voice;

import com.google.gson.JsonParser;
import dev.danteai.llm.HttpSupport;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Voz a texto con la API {@code POST {base}/audio/transcriptions} (OpenAI Whisper y servidores compatibles como
 * Speaches, LocalAI o whisper.cpp server). Envía un WAV mono de 16 kHz como multipart/form-data.
 */
public final class TranscriptionProvider {
	public static final int SAMPLE_RATE = 16_000;

	public record Settings(String baseUrl, String apiKey, String model, String language, Duration timeout) {
	}

	private final Settings settings;

	public TranscriptionProvider(Settings settings) {
		this.settings = settings;
	}

	public boolean configured() {
		return !settings.baseUrl.contains("api.openai.com") || !settings.apiKey.isBlank();
	}

	/** @param pcm audio mono de 16 bits a {@code sampleRate} */
	public CompletableFuture<String> transcribe(short[] pcm, int sampleRate) {
		short[] resampled = AudioUtil.resample(pcm, sampleRate, SAMPLE_RATE, 1.0);
		byte[] wav = AudioUtil.encodeWav(resampled, SAMPLE_RATE);
		String boundary = "----danteai" + UUID.randomUUID().toString().replace("-", "");
		byte[] body = multipart(boundary, wav);
		Map<String, String> headers = new LinkedHashMap<>();
		headers.put("Content-Type", "multipart/form-data; boundary=" + boundary);
		if (!settings.apiKey.isBlank()) headers.put("Authorization", "Bearer " + settings.apiKey.strip());
		URI uri = URI.create(OpenAiTtsProvider.stripSlash(settings.baseUrl) + "/audio/transcriptions");
		return HttpSupport.post(uri, headers, HttpRequest.BodyPublishers.ofByteArray(body), settings.timeout)
			.thenApply(bytes -> parse(new String(bytes, StandardCharsets.UTF_8)));
	}

	byte[] multipart(String boundary, byte[] wav) {
		ByteArrayOutputStream out = new ByteArrayOutputStream(wav.length + 1024);
		field(out, boundary, "model", settings.model);
		field(out, boundary, "response_format", "json");
		if (!settings.language.isBlank()) field(out, boundary, "language", settings.language);
		write(out, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"voice.wav\"\r\nContent-Type: audio/wav\r\n\r\n");
		out.writeBytes(wav);
		write(out, "\r\n--" + boundary + "--\r\n");
		return out.toByteArray();
	}

	private static void field(ByteArrayOutputStream out, String boundary, String name, String value) {
		write(out, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n");
	}

	private static void write(ByteArrayOutputStream out, String s) {
		out.writeBytes(s.getBytes(StandardCharsets.UTF_8));
	}

	static String parse(String body) {
		String trimmed = body.strip();
		if (trimmed.startsWith("{")) {
			var obj = JsonParser.parseString(trimmed).getAsJsonObject();
			return obj.has("text") ? obj.get("text").getAsString().strip() : "";
		}
		return trimmed;
	}
}
