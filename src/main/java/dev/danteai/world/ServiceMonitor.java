package dev.danteai.world;

import dev.danteai.config.DanteConfig;
import dev.danteai.llm.HttpSupport;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * Comprueba periódicamente que los servicios de los que depende Dante (LLM, voz y escucha) respondan.
 * <p>
 * Un servicio se considera caído desde el principio y hasta que responde una vez; después tolera
 * {@value #FAIL_THRESHOLD} fallos seguidos antes de volver a considerarse caído, para que un corte breve no haga
 * desaparecer a Dante.
 */
public final class ServiceMonitor {
	public record Target(String name, String url) {
	}

	@FunctionalInterface
	public interface Probe {
		CompletableFuture<Boolean> reachable(String url);
	}

	static final int FAIL_THRESHOLD = 3;
	private static final long PROBE_WAIT_SECONDS = 6;

	private final Probe probe;
	private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "dante-service-monitor");
		t.setDaemon(true);
		return t;
	});
	private final Map<Target, Integer> failures = new LinkedHashMap<>();
	private volatile List<Target> targets = List.of();
	private volatile boolean available;
	private volatile List<String> missing = List.of();
	private volatile BiConsumer<Boolean, List<String>> listener = (ok, names) -> {
	};
	private ScheduledFuture<?> periodic;
	private boolean reported;

	public ServiceMonitor(Probe probe) {
		this.probe = probe;
	}

	/** Servicios de los que depende Dante según la configuración. */
	public static List<Target> targets(DanteConfig c) {
		List<Target> list = new ArrayList<>();
		if (c.llm.enabled) {
			String url = switch (c.llm.provider) {
				case "anthropic" -> c.llm.anthropic.base_url;
				default -> c.llm.openai.base_url;
			};
			add(list, "LLM", url);
		}
		if (c.voice.enabled) {
			add(list, "voz (TTS)", c.voice.openai.base_url);
			if (c.voice.listen.enabled) add(list, "escucha (STT)", c.voice.listen.base_url);
		}
		return list;
	}

	private static void add(List<Target> list, String name, String url) {
		if (url != null && !url.isBlank()) list.add(new Target(name, url.strip()));
	}

	/** Cualquier respuesta HTTP cuenta como "alcanzable", salvo los errores típicos de un proxy sin destino. */
	public static CompletableFuture<Boolean> httpProbe(String url) {
		URI uri;
		try {
			uri = URI.create(url);
		} catch (RuntimeException e) {
			return CompletableFuture.completedFuture(false);
		}
		if (uri.getHost() == null) return CompletableFuture.completedFuture(false);
		HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(3)).GET().build();
		return HttpSupport.client().sendAsync(request, HttpResponse.BodyHandlers.discarding())
			.handle((response, error) -> error == null && response.statusCode() != 502 && response.statusCode() != 503
				&& response.statusCode() != 504);
	}

	/** Se invoca cuando cambia el estado general: {@code true} y lista vacía, o {@code false} y los servicios caídos. */
	public void setListener(BiConsumer<Boolean, List<String>> listener) {
		this.listener = listener;
	}

	/** Aplica nuevos servicios e intervalo; reinicia la comprobación periódica. No bloquea. */
	public void configure(List<Target> newTargets, int intervalSeconds) {
		synchronized (failures) {
			Map<Target, Integer> old = new LinkedHashMap<>(failures);
			failures.clear();
			for (Target t : newTargets) failures.put(t, old.getOrDefault(t, FAIL_THRESHOLD));
			targets = List.copyOf(newTargets);
			if (periodic != null) periodic.cancel(false);
			periodic = executor.scheduleWithFixedDelay(this::safeCheck, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
		}
	}

	/** Lanza una comprobación inmediata en segundo plano. */
	public void refresh() {
		executor.execute(this::safeCheck);
	}

	/** {@code true} si todos los servicios requeridos respondieron (o no hay ninguno que requerir). */
	public boolean available() {
		return available;
	}

	/** Nombres de los servicios que no responden. */
	public List<String> missing() {
		return missing;
	}

	/** Comprobación inmediata y bloqueante (como máximo unos segundos). */
	public void checkNow() {
		safeCheck();
	}

	public void shutdown() {
		executor.shutdownNow();
	}

	private void safeCheck() {
		try {
			check();
		} catch (RuntimeException e) {
			// Una comprobación fallida no debe detener las siguientes.
		}
	}

	synchronized void check() {
		List<Target> current = targets;
		Map<Target, CompletableFuture<Boolean>> pending = new LinkedHashMap<>();
		for (Target t : current) {
			CompletableFuture<Boolean> f;
			try {
				f = probe.reachable(t.url());
			} catch (RuntimeException e) {
				f = CompletableFuture.completedFuture(false);
			}
			pending.put(t, f);
		}
		Map<Target, Boolean> results = new LinkedHashMap<>();
		pending.forEach((t, f) -> {
			boolean ok;
			try {
				ok = Boolean.TRUE.equals(f.get(PROBE_WAIT_SECONDS, TimeUnit.SECONDS));
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				ok = false;
			} catch (Exception e) {
				f.cancel(true);
				ok = false;
			}
			results.put(t, ok);
		});
		List<String> down = new ArrayList<>();
		boolean changed;
		synchronized (failures) {
			if (targets != current) return;
			results.forEach((t, ok) -> failures.put(t, ok ? 0 : Math.min(FAIL_THRESHOLD, failures.getOrDefault(t, FAIL_THRESHOLD) + 1)));
			for (Target t : current) {
				if (failures.getOrDefault(t, FAIL_THRESHOLD) >= FAIL_THRESHOLD) down.add(t.name());
			}
			boolean ok = down.isEmpty();
			changed = !reported || ok != available || !down.equals(missing);
			missing = List.copyOf(down);
			available = ok;
			reported = true;
		}
		if (changed) listener.accept(available, missing);
	}
}
