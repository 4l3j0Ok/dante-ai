package dev.danteai.memory;

import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Memoria en SQLite (driver xerial sqlite-jdbc empaquetado en el mod). Se usa {@link SQLiteDataSource}
 * directamente en lugar de {@code DriverManager}, que no ve drivers cargados por el class loader de Fabric.
 */
public final class SqliteMemoryStore implements MemoryStore {
	private static final int SCHEMA_VERSION = 1;
	private final Connection connection;

	public SqliteMemoryStore(Path file) throws SQLException {
		try {
			Files.createDirectories(file.toAbsolutePath().getParent());
		} catch (java.io.IOException e) {
			throw new SQLException("No se pudo crear el directorio de memoria", e);
		}
		SQLiteConfig config = new SQLiteConfig();
		config.setJournalMode(SQLiteConfig.JournalMode.WAL);
		config.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
		config.setBusyTimeout(5000);
		SQLiteDataSource ds = new SQLiteDataSource(config);
		ds.setUrl("jdbc:sqlite:" + file.toAbsolutePath());
		this.connection = ds.getConnection();
		migrate();
	}

	private void migrate() throws SQLException {
		try (Statement st = connection.createStatement()) {
			st.executeUpdate("""
				CREATE TABLE IF NOT EXISTS players (
				  uuid TEXT PRIMARY KEY,
				  name TEXT NOT NULL,
				  first_seen INTEGER NOT NULL,
				  last_seen INTEGER NOT NULL,
				  interactions INTEGER NOT NULL DEFAULT 0,
				  summary TEXT NOT NULL DEFAULT ''
				)""");
			st.executeUpdate("""
				CREATE TABLE IF NOT EXISTS messages (
				  id INTEGER PRIMARY KEY AUTOINCREMENT,
				  player TEXT NOT NULL,
				  role TEXT NOT NULL,
				  content TEXT NOT NULL,
				  created_at INTEGER NOT NULL
				)""");
			st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_messages_player ON messages(player, id)");
			st.executeUpdate("""
				CREATE TABLE IF NOT EXISTS facts (
				  id INTEGER PRIMARY KEY AUTOINCREMENT,
				  player TEXT NOT NULL,
				  kind TEXT NOT NULL,
				  content TEXT NOT NULL,
				  importance INTEGER NOT NULL DEFAULT 1,
				  created_at INTEGER NOT NULL,
				  resolved INTEGER NOT NULL DEFAULT 0
				)""");
			st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_facts_player ON facts(player, resolved)");
			st.executeUpdate("PRAGMA user_version = " + SCHEMA_VERSION);
		}
	}

	@Override
	public void touchPlayer(UUID player, String name, long now) {
		update("""
			INSERT INTO players(uuid, name, first_seen, last_seen, interactions) VALUES (?, ?, ?, ?, 1)
			ON CONFLICT(uuid) DO UPDATE SET name = excluded.name, last_seen = excluded.last_seen, interactions = interactions + 1""",
			player.toString(), name, now, now);
	}

	@Override
	public PlayerProfile profile(UUID player) {
		List<PlayerProfile> list = queryProfiles("SELECT * FROM players WHERE uuid = ?", player.toString());
		return list.isEmpty() ? null : list.getFirst();
	}

	@Override
	public List<PlayerProfile> knownPlayers(int limit) {
		return queryProfiles("SELECT * FROM players ORDER BY last_seen DESC LIMIT ?", limit);
	}

	private List<PlayerProfile> queryProfiles(String sql, Object... args) {
		List<PlayerProfile> out = new ArrayList<>();
		try (PreparedStatement ps = prepare(sql, args); ResultSet rs = ps.executeQuery()) {
			while (rs.next()) {
				out.add(new PlayerProfile(UUID.fromString(rs.getString("uuid")), rs.getString("name"), rs.getLong("first_seen"),
					rs.getLong("last_seen"), rs.getInt("interactions"), rs.getString("summary")));
			}
		} catch (SQLException e) {
			throw new MemoryException(e);
		}
		return out;
	}

	@Override
	public void setSummary(UUID player, String summary) {
		update("UPDATE players SET summary = ? WHERE uuid = ?", summary, player.toString());
	}

	@Override
	public void addMessage(UUID player, String role, String content, long now) {
		update("INSERT INTO messages(player, role, content, created_at) VALUES (?, ?, ?, ?)", player.toString(), role, content, now);
	}

	@Override
	public List<StoredMessage> recentMessages(UUID player, int limit) {
		List<StoredMessage> list = queryMessages("SELECT * FROM messages WHERE player = ? ORDER BY id DESC LIMIT ?", player.toString(), limit);
		Collections.reverse(list);
		return list;
	}

	@Override
	public List<StoredMessage> oldestMessages(UUID player, int limit) {
		return queryMessages("SELECT * FROM messages WHERE player = ? ORDER BY id ASC LIMIT ?", player.toString(), limit);
	}

	private List<StoredMessage> queryMessages(String sql, Object... args) {
		List<StoredMessage> out = new ArrayList<>();
		try (PreparedStatement ps = prepare(sql, args); ResultSet rs = ps.executeQuery()) {
			while (rs.next()) {
				out.add(new StoredMessage(rs.getLong("id"), UUID.fromString(rs.getString("player")), rs.getString("role"),
					rs.getString("content"), rs.getLong("created_at")));
			}
		} catch (SQLException e) {
			throw new MemoryException(e);
		}
		return out;
	}

	@Override
	public int countMessages(UUID player) {
		try (PreparedStatement ps = prepare("SELECT COUNT(*) FROM messages WHERE player = ?", player.toString()); ResultSet rs = ps.executeQuery()) {
			return rs.next() ? rs.getInt(1) : 0;
		} catch (SQLException e) {
			throw new MemoryException(e);
		}
	}

	@Override
	public void deleteMessagesUpTo(UUID player, long maxIdInclusive) {
		update("DELETE FROM messages WHERE player = ? AND id <= ?", player.toString(), maxIdInclusive);
	}

	@Override
	public long addFact(UUID player, String kind, String content, int importance, long now) {
		try (PreparedStatement ps = connection.prepareStatement(
				"INSERT INTO facts(player, kind, content, importance, created_at) VALUES (?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
			bind(ps, player.toString(), kind, content, importance, now);
			ps.executeUpdate();
			try (ResultSet keys = ps.getGeneratedKeys()) {
				return keys.next() ? keys.getLong(1) : -1;
			}
		} catch (SQLException e) {
			throw new MemoryException(e);
		}
	}

	@Override
	public List<Fact> facts(UUID player, int limit) {
		List<Fact> out = new ArrayList<>();
		try (PreparedStatement ps = prepare(
				"SELECT * FROM facts WHERE player = ? AND resolved = 0 ORDER BY importance DESC, id DESC LIMIT ?", player.toString(), limit);
				ResultSet rs = ps.executeQuery()) {
			while (rs.next()) {
				out.add(new Fact(rs.getLong("id"), UUID.fromString(rs.getString("player")), rs.getString("kind"), rs.getString("content"),
					rs.getInt("importance"), rs.getLong("created_at"), rs.getInt("resolved") != 0));
			}
		} catch (SQLException e) {
			throw new MemoryException(e);
		}
		return out;
	}

	@Override
	public boolean resolveFact(long id) {
		return update("UPDATE facts SET resolved = 1 WHERE id = ? AND resolved = 0", id) > 0;
	}

	@Override
	public void prune(long olderThanMillis) {
		update("DELETE FROM messages WHERE created_at < ?", olderThanMillis);
		update("DELETE FROM facts WHERE created_at < ? AND (resolved = 1 OR importance < 3)", olderThanMillis);
	}

	@Override
	public void forgetPlayer(UUID player) {
		update("DELETE FROM messages WHERE player = ?", player.toString());
		update("DELETE FROM facts WHERE player = ?", player.toString());
		update("DELETE FROM players WHERE uuid = ?", player.toString());
	}

	@Override
	public void clearAll() {
		update("DELETE FROM messages");
		update("DELETE FROM facts");
		update("DELETE FROM players");
	}

	@Override
	public boolean persistent() {
		return true;
	}

	@Override
	public void close() {
		try {
			connection.close();
		} catch (SQLException ignored) {
		}
	}

	private int update(String sql, Object... args) {
		try (PreparedStatement ps = prepare(sql, args)) {
			return ps.executeUpdate();
		} catch (SQLException e) {
			throw new MemoryException(e);
		}
	}

	private PreparedStatement prepare(String sql, Object... args) throws SQLException {
		PreparedStatement ps = connection.prepareStatement(sql);
		bind(ps, args);
		return ps;
	}

	private static void bind(PreparedStatement ps, Object... args) throws SQLException {
		for (int i = 0; i < args.length; i++) {
			Object a = args[i];
			switch (a) {
				case String s -> ps.setString(i + 1, s);
				case Integer n -> ps.setInt(i + 1, n);
				case Long n -> ps.setLong(i + 1, n);
				default -> ps.setObject(i + 1, a);
			}
		}
	}

	public static final class MemoryException extends RuntimeException {
		MemoryException(Throwable cause) {
			super(cause);
		}
	}
}
