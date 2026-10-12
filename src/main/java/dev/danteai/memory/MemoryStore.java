package dev.danteai.memory;

import java.util.List;
import java.util.UUID;

/**
 * Almacenamiento de memoria de Dante. Las implementaciones no son thread-safe por sí mismas:
 * {@link MemoryService} serializa todos los accesos en un único hilo de fondo.
 */
public interface MemoryStore extends AutoCloseable {
	/** Memoria global (no asociada a un jugador). */
	UUID GLOBAL = new UUID(0L, 0L);

	record StoredMessage(long id, UUID player, String role, String content, long timestamp) {
	}

	record Fact(long id, UUID player, String kind, String content, int importance, long timestamp, boolean resolved) {
	}

	record PlayerProfile(UUID player, String name, long firstSeen, long lastSeen, int interactions, String summary) {
	}

	void touchPlayer(UUID player, String name, long now);

	PlayerProfile profile(UUID player);

	List<PlayerProfile> knownPlayers(int limit);

	void setSummary(UUID player, String summary);

	void addMessage(UUID player, String role, String content, long now);

	List<StoredMessage> recentMessages(UUID player, int limit);

	List<StoredMessage> oldestMessages(UUID player, int limit);

	int countMessages(UUID player);

	void deleteMessagesUpTo(UUID player, long maxIdInclusive);

	long addFact(UUID player, String kind, String content, int importance, long now);

	/** Hechos no resueltos, ordenados por importancia y recencia. */
	List<Fact> facts(UUID player, int limit);

	boolean resolveFact(long id);

	void prune(long olderThanMillis);

	void forgetPlayer(UUID player);

	void clearAll();

	boolean persistent();

	@Override
	void close();
}
