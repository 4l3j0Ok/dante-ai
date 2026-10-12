package dev.danteai.memory;

import dev.danteai.util.DanteLog;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Fachada asincrónica de memoria. Todo el acceso a disco ocurre en un único hilo "danteai-memory", nunca en
 * el hilo del servidor. Aplica límites de contexto, resumen de historial y retención.
 */
public final class MemoryService {
	/** Contexto de memoria que se agrega al prompt. */
	public record Context(MemoryStore.PlayerProfile profile, List<MemoryStore.StoredMessage> recent,
			List<MemoryStore.Fact> playerFacts, List<MemoryStore.Fact> globalFacts, List<MemoryStore.PlayerProfile> knownPlayers) {
		public static Context empty() {
			return new Context(null, List.of(), List.of(), List.of(), List.of());
		}
	}

	public record Settings(boolean enabled, int contextMessages, int summarizeAfter, int contextFacts, int retentionDays) {
	}

	private final MemoryStore store;
	private final ExecutorService executor;
	private volatile Settings settings;
	private volatile Function<String, CompletableFuture<String>> summarizer;

	public MemoryService(MemoryStore store, Settings settings) {
		this.store = store;
		this.settings = settings;
		this.executor = Executors.newSingleThreadExecutor(r -> {
			Thread t = new Thread(r, "danteai-memory");
			t.setDaemon(true);
			return t;
		});
	}

	/** Abre SQLite; si falla (plataforma sin soporte nativo, disco, etc.) usa memoria en RAM. */
	public static MemoryStore openStore(Path file, boolean persist) {
		if (!persist) {
			return new InMemoryMemoryStore();
		}
		try {
			return new SqliteMemoryStore(file);
		} catch (Throwable t) {
			DanteLog.error("No se pudo abrir la memoria SQLite (" + file + "). Se usará memoria temporal en RAM.", t);
			return new InMemoryMemoryStore();
		}
	}

	public void updateSettings(Settings settings) {
		this.settings = settings;
	}

	public void setSummarizer(Function<String, CompletableFuture<String>> summarizer) {
		this.summarizer = summarizer;
	}

	public boolean persistent() {
		return store.persistent();
	}

	private <T> CompletableFuture<T> submit(Supplier<T> work, T fallback) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				return work.get();
			} catch (RuntimeException e) {
				DanteLog.error("Error de memoria", e);
				return fallback;
			}
		}, executor);
	}

	public CompletableFuture<Context> loadContext(UUID player, String name) {
		Settings s = settings;
		if (!s.enabled()) return CompletableFuture.completedFuture(Context.empty());
		return submit(() -> {
			store.touchPlayer(player, name, System.currentTimeMillis());
			return new Context(store.profile(player), store.recentMessages(player, s.contextMessages()),
				store.facts(player, s.contextFacts()), store.facts(MemoryStore.GLOBAL, Math.max(3, s.contextFacts() / 2)),
				store.knownPlayers(12));
		}, Context.empty());
	}

	public void recordExchange(UUID player, String userText, String assistantText) {
		Settings s = settings;
		if (!s.enabled()) return;
		submit(() -> {
			long now = System.currentTimeMillis();
			if (userText != null && !userText.isBlank()) store.addMessage(player, "user", userText, now);
			if (assistantText != null && !assistantText.isBlank()) store.addMessage(player, "assistant", assistantText, now);
			return null;
		}, null).thenRun(() -> maybeSummarize(player));
	}

	public CompletableFuture<Long> remember(UUID player, String kind, String content, int importance) {
		if (!settings.enabled()) return CompletableFuture.completedFuture(-1L);
		String clean = content.length() > 300 ? content.substring(0, 300) : content;
		return submit(() -> store.addFact(player == null ? MemoryStore.GLOBAL : player, kind, clean,
			Math.max(1, Math.min(5, importance)), System.currentTimeMillis()), -1L);
	}

	public CompletableFuture<Boolean> resolve(long factId) {
		return submit(() -> store.resolveFact(factId), false);
	}

	public CompletableFuture<Void> forgetPlayer(UUID player) {
		return submit(() -> {
			store.forgetPlayer(player);
			return null;
		}, null);
	}

	public CompletableFuture<Void> clearAll() {
		return submit(() -> {
			store.clearAll();
			return null;
		}, null);
	}

	public CompletableFuture<List<MemoryStore.Fact>> facts(UUID player, int limit) {
		return submit(() -> store.facts(player, limit), List.of());
	}

	public void pruneOld() {
		int days = settings.retentionDays();
		if (days <= 0) return;
		submit(() -> {
			store.prune(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days));
			return null;
		}, null);
	}

	/**
	 * Cuando un jugador acumula demasiados mensajes, los más viejos se condensan en un resumen y se borran.
	 * Si hay un resumidor LLM se usa; si no, se guarda un extracto recortado.
	 */
	private void maybeSummarize(UUID player) {
		Settings s = settings;
		submit(() -> {
			int count = store.countMessages(player);
			if (count <= s.summarizeAfter()) return null;
			List<MemoryStore.StoredMessage> old = store.oldestMessages(player, count - s.contextMessages());
			if (old.isEmpty()) return null;
			MemoryStore.PlayerProfile profile = store.profile(player);
			String previous = profile == null ? "" : profile.summary();
			StringBuilder transcript = new StringBuilder();
			for (MemoryStore.StoredMessage m : old) {
				transcript.append(m.role().equals("user") ? "Jugador: " : "Dante: ").append(m.content()).append('\n');
			}
			long lastId = old.getLast().id();
			Function<String, CompletableFuture<String>> fn = summarizer;
			String input = "Resumen previo: " + (previous.isBlank() ? "(ninguno)" : previous) + "\n\nConversación nueva:\n" + transcript;
			CompletableFuture<String> summary = fn != null
				? fn.apply(input).exceptionally(e -> naiveSummary(previous, transcript.toString()))
				: CompletableFuture.completedFuture(naiveSummary(previous, transcript.toString()));
			summary.thenAcceptAsync(text -> {
				store.setSummary(player, text.length() > 1200 ? text.substring(0, 1200) : text);
				store.deleteMessagesUpTo(player, lastId);
			}, executor);
			return null;
		}, null);
	}

	static String naiveSummary(String previous, String transcript) {
		String combined = (previous == null || previous.isBlank() ? "" : previous + " | ") + transcript.replace('\n', ' ');
		return combined.length() > 1000 ? "…" + combined.substring(combined.length() - 1000) : combined;
	}

	public void close() {
		executor.shutdown();
		try {
			executor.awaitTermination(5, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		store.close();
	}
}
