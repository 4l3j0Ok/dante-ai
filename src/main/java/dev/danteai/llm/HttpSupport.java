package dev.danteai.llm;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** Utilidades HTTP asincrónicas compartidas por los proveedores de texto y de voz. */
public final class HttpSupport {
	private static final HttpClient CLIENT = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(10))
		.followRedirects(HttpClient.Redirect.NORMAL)
		.build();

	private HttpSupport() {
	}

	public static HttpClient client() {
		return CLIENT;
	}

	/** POST con cuerpo de texto. Los códigos != 2xx se convierten en {@link LlmException}. */
	public static CompletableFuture<byte[]> post(URI uri, Map<String, String> headers, HttpRequest.BodyPublisher body, Duration timeout) {
		HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(timeout).POST(body);
		headers.forEach((k, v) -> {
			if (v != null && !v.isEmpty()) builder.header(k, v);
		});
		CompletableFuture<HttpResponse<byte[]>> http = CLIENT.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
		CompletableFuture<byte[]> result = http.handle((response, error) -> {
			if (error != null) {
				throw mapError(error);
			}
			int status = response.statusCode();
			if (status < 200 || status >= 300) {
				String text = new String(response.body(), java.nio.charset.StandardCharsets.UTF_8);
				long retryAfter = response.headers().firstValue("retry-after").map(HttpSupport::parseRetryAfter).orElse(-1L);
				throw new LlmException("HTTP " + status + ": " + abbreviate(text, 400), status,
					LlmException.isRetryableStatus(status), retryAfter, null);
			}
			return response.body();
		});
		// Cancelar el resultado aborta el intercambio HTTP.
		result.whenComplete((r, e) -> {
			if (result.isCancelled()) http.cancel(true);
		});
		return result;
	}

	public static CompletableFuture<String> postJson(URI uri, Map<String, String> headers, String json, Duration timeout) {
		java.util.Map<String, String> all = new java.util.LinkedHashMap<>(headers);
		all.put("Content-Type", "application/json");
		return post(uri, all, HttpRequest.BodyPublishers.ofString(json), timeout)
			.thenApply(bytes -> new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
	}

	static RuntimeException mapError(Throwable error) {
		Throwable cause = unwrap(error);
		if (cause instanceof LlmException e) return e;
		if (cause instanceof CancellationException e) return e;
		if (cause instanceof HttpTimeoutException) {
			return new LlmException("Tiempo de espera agotado", -1, true, -1, cause);
		}
		if (cause instanceof IOException) {
			return new LlmException("Error de red: " + cause.getMessage(), -1, true, -1, cause);
		}
		return new LlmException("Error inesperado: " + cause, -1, false, -1, cause);
	}

	public static Throwable unwrap(Throwable t) {
		while ((t instanceof CompletionException || t instanceof java.util.concurrent.ExecutionException) && t.getCause() != null) {
			t = t.getCause();
		}
		return t;
	}

	static long parseRetryAfter(String value) {
		try {
			return (long) (Double.parseDouble(value.strip()) * 1000);
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	public static String abbreviate(String s, int max) {
		if (s == null) return "";
		return s.length() <= max ? s : s.substring(0, max) + "…";
	}

	/**
	 * Ejecuta {@code attempt} con reintentos acotados y backoff exponencial con jitter. Solo reintenta errores
	 * marcados como reintentables. Cancelar el future devuelto cancela el intento en curso y los siguientes.
	 */
	public static <T> CompletableFuture<T> withRetries(Supplier<CompletableFuture<T>> attempt, int maxRetries, long baseDelayMillis) {
		CompletableFuture<T> result = new CompletableFuture<>();
		AtomicReference<CompletableFuture<T>> current = new AtomicReference<>();
		result.whenComplete((r, e) -> {
			if (result.isCancelled()) {
				CompletableFuture<T> c = current.get();
				if (c != null) c.cancel(true);
			}
		});
		runAttempt(attempt, 0, maxRetries, baseDelayMillis, result, current);
		return result;
	}

	private static <T> void runAttempt(Supplier<CompletableFuture<T>> attempt, int n, int maxRetries, long baseDelay,
			CompletableFuture<T> result, AtomicReference<CompletableFuture<T>> current) {
		if (result.isDone()) return;
		CompletableFuture<T> f;
		try {
			f = attempt.get();
		} catch (RuntimeException e) {
			result.completeExceptionally(e);
			return;
		}
		current.set(f);
		f.whenComplete((value, error) -> {
			if (error == null) {
				result.complete(value);
				return;
			}
			Throwable cause = unwrap(error);
			boolean retryable = cause instanceof LlmException le && le.retryable();
			if (!retryable || n >= maxRetries || result.isDone()) {
				result.completeExceptionally(cause);
				return;
			}
			long delay = baseDelay * (1L << n) + ThreadLocalRandom.current().nextLong(Math.max(1, baseDelay / 2));
			if (cause instanceof LlmException le && le.retryAfterMillis() > 0) {
				delay = Math.max(delay, Math.min(le.retryAfterMillis(), 30_000));
			}
			CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS)
				.execute(() -> runAttempt(attempt, n + 1, maxRetries, baseDelay, result, current));
		});
	}
}
