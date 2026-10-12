package dev.danteai.test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.Function;

/** Servidor HTTP local para probar los proveedores sin red externa. Graba cada solicitud recibida. */
public final class MockHttpServer implements AutoCloseable {
	public record Recorded(String method, String path, Map<String, List<String>> headers, byte[] body) {
		public String bodyText() {
			return new String(body, StandardCharsets.UTF_8);
		}

		public String header(String name) {
			for (var e : headers.entrySet()) {
				if (e.getKey().equalsIgnoreCase(name)) return e.getValue().getFirst();
			}
			return null;
		}
	}

	public record Reply(int status, byte[] body, Map<String, String> headers, long delayMillis) {
		public static Reply json(int status, String json) {
			return new Reply(status, json.getBytes(StandardCharsets.UTF_8), Map.of("Content-Type", "application/json"), 0);
		}

		public static Reply bytes(byte[] body) {
			return new Reply(200, body, Map.of("Content-Type", "application/octet-stream"), 0);
		}

		public Reply delayed(long millis) {
			return new Reply(status, body, headers, millis);
		}
	}

	private final HttpServer server;
	private final List<Recorded> requests = Collections.synchronizedList(new ArrayList<>());
	private final ConcurrentLinkedDeque<Function<Recorded, Reply>> replies = new ConcurrentLinkedDeque<>();
	private volatile Function<Recorded, Reply> fallback = r -> Reply.json(500, "{\"error\":\"sin respuesta programada\"}");

	public MockHttpServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", this::handle);
		server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
		server.start();
	}

	public String baseUrl() {
		return "http://127.0.0.1:" + server.getAddress().getPort();
	}

	/** Programa respuestas en orden; cuando se agotan se usa la de respaldo. */
	public MockHttpServer enqueue(Reply reply) {
		replies.addLast(r -> reply);
		return this;
	}

	public MockHttpServer always(Function<Recorded, Reply> handler) {
		this.fallback = handler;
		return this;
	}

	public List<Recorded> requests() {
		return List.copyOf(requests);
	}

	private void handle(HttpExchange exchange) throws IOException {
		byte[] body = exchange.getRequestBody().readAllBytes();
		Recorded rec = new Recorded(exchange.getRequestMethod(), exchange.getRequestURI().toString(), exchange.getRequestHeaders(), body);
		requests.add(rec);
		Function<Recorded, Reply> handler = replies.pollFirst();
		Reply reply = (handler == null ? fallback : handler).apply(rec);
		if (reply.delayMillis() > 0) {
			try {
				Thread.sleep(reply.delayMillis());
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		reply.headers().forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
		exchange.sendResponseHeaders(reply.status(), reply.body().length == 0 ? -1 : reply.body().length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(reply.body());
		} catch (IOException ignored) {
			// El cliente pudo haber cancelado.
		}
	}

	@Override
	public void close() {
		server.stop(0);
	}
}
