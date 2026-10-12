package dev.danteai.memory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Memoria volátil: se usa si la persistencia está desactivada o si SQLite no está disponible en la plataforma. */
public final class InMemoryMemoryStore implements MemoryStore {
	private final Map<UUID, PlayerProfile> players = new HashMap<>();
	private final List<StoredMessage> messages = new ArrayList<>();
	private final List<Fact> facts = new ArrayList<>();
	private long nextId = 1;

	@Override
	public void touchPlayer(UUID player, String name, long now) {
		PlayerProfile old = players.get(player);
		players.put(player, old == null
			? new PlayerProfile(player, name, now, now, 1, "")
			: new PlayerProfile(player, name, old.firstSeen(), now, old.interactions() + 1, old.summary()));
	}

	@Override
	public PlayerProfile profile(UUID player) {
		return players.get(player);
	}

	@Override
	public List<PlayerProfile> knownPlayers(int limit) {
		return players.values().stream().sorted(Comparator.comparingLong(PlayerProfile::lastSeen).reversed()).limit(limit).toList();
	}

	@Override
	public void setSummary(UUID player, String summary) {
		PlayerProfile p = players.get(player);
		if (p != null) players.put(player, new PlayerProfile(player, p.name(), p.firstSeen(), p.lastSeen(), p.interactions(), summary));
	}

	@Override
	public void addMessage(UUID player, String role, String content, long now) {
		messages.add(new StoredMessage(nextId++, player, role, content, now));
	}

	@Override
	public List<StoredMessage> recentMessages(UUID player, int limit) {
		List<StoredMessage> own = messages.stream().filter(m -> m.player().equals(player)).toList();
		return own.subList(Math.max(0, own.size() - limit), own.size());
	}

	@Override
	public List<StoredMessage> oldestMessages(UUID player, int limit) {
		return messages.stream().filter(m -> m.player().equals(player)).limit(limit).toList();
	}

	@Override
	public int countMessages(UUID player) {
		return (int) messages.stream().filter(m -> m.player().equals(player)).count();
	}

	@Override
	public void deleteMessagesUpTo(UUID player, long maxIdInclusive) {
		messages.removeIf(m -> m.player().equals(player) && m.id() <= maxIdInclusive);
	}

	@Override
	public long addFact(UUID player, String kind, String content, int importance, long now) {
		long id = nextId++;
		facts.add(new Fact(id, player, kind, content, importance, now, false));
		return id;
	}

	@Override
	public List<Fact> facts(UUID player, int limit) {
		return facts.stream().filter(f -> f.player().equals(player) && !f.resolved())
			.sorted(Comparator.comparingInt(Fact::importance).reversed().thenComparing(Comparator.comparingLong(Fact::id).reversed()))
			.limit(limit).toList();
	}

	@Override
	public boolean resolveFact(long id) {
		for (int i = 0; i < facts.size(); i++) {
			Fact f = facts.get(i);
			if (f.id() == id && !f.resolved()) {
				facts.set(i, new Fact(f.id(), f.player(), f.kind(), f.content(), f.importance(), f.timestamp(), true));
				return true;
			}
		}
		return false;
	}

	@Override
	public void prune(long olderThanMillis) {
		messages.removeIf(m -> m.timestamp() < olderThanMillis);
		facts.removeIf(f -> f.timestamp() < olderThanMillis && (f.resolved() || f.importance() < 3));
	}

	@Override
	public void forgetPlayer(UUID player) {
		players.remove(player);
		messages.removeIf(m -> m.player().equals(player));
		facts.removeIf(f -> f.player().equals(player));
	}

	@Override
	public void clearAll() {
		players.clear();
		messages.clear();
		facts.clear();
	}

	@Override
	public boolean persistent() {
		return false;
	}

	@Override
	public void close() {
	}
}
