package dev.danteai.test.memory;

import dev.danteai.memory.InMemoryMemoryStore;
import dev.danteai.memory.MemoryService;
import dev.danteai.memory.MemoryStore;
import dev.danteai.memory.SqliteMemoryStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryStoreTest {
	private static void exercise(MemoryStore store) {
		UUID alejo = UUID.randomUUID();
		UUID ana = UUID.randomUUID();
		store.touchPlayer(alejo, "Alejo", 1000);
		store.touchPlayer(alejo, "Alejo", 2000);
		store.touchPlayer(ana, "Ana", 3000);
		MemoryStore.PlayerProfile p = store.profile(alejo);
		assertEquals("Alejo", p.name());
		assertEquals(1000, p.firstSeen());
		assertEquals(2000, p.lastSeen());
		assertEquals(2, p.interactions());
		assertEquals("Ana", store.knownPlayers(10).getFirst().name(), "ordenados por última vez visto");

		for (int i = 0; i < 6; i++) store.addMessage(alejo, i % 2 == 0 ? "user" : "assistant", "m" + i, 5000 + i);
		store.addMessage(ana, "user", "hola", 6000);
		List<MemoryStore.StoredMessage> recent = store.recentMessages(alejo, 3);
		assertEquals(List.of("m3", "m4", "m5"), recent.stream().map(MemoryStore.StoredMessage::content).toList());
		assertEquals(6, store.countMessages(alejo));
		List<MemoryStore.StoredMessage> oldest = store.oldestMessages(alejo, 2);
		store.deleteMessagesUpTo(alejo, oldest.getLast().id());
		assertEquals(4, store.countMessages(alejo));
		assertEquals(1, store.countMessages(ana), "no toca a otros jugadores");

		long promise = store.addFact(alejo, "commitment", "Llevarle huesos mañana", 4, 7000);
		store.addFact(alejo, "preference", "Le gustan las galletitas", 2, 7001);
		store.addFact(MemoryStore.GLOBAL, "event", "Vimos un creeper enorme", 3, 7002);
		List<MemoryStore.Fact> facts = store.facts(alejo, 10);
		assertEquals(2, facts.size());
		assertEquals("commitment", facts.getFirst().kind(), "más importantes primero");
		assertTrue(store.resolveFact(promise));
		assertFalse(store.resolveFact(promise), "no se resuelve dos veces");
		assertEquals(1, store.facts(alejo, 10).size());
		assertEquals(1, store.facts(MemoryStore.GLOBAL, 10).size());

		store.setSummary(alejo, "Alejo es minero");
		assertEquals("Alejo es minero", store.profile(alejo).summary());

		store.prune(5003);
		assertEquals(3, store.countMessages(alejo), "borra mensajes viejos");

		store.forgetPlayer(alejo);
		assertNull(store.profile(alejo));
		assertEquals(0, store.countMessages(alejo));
		assertNotNull(store.profile(ana));
		store.clearAll();
		assertNull(store.profile(ana));
	}

	@Test
	void sqliteStoreWorksAndPersistsAcrossReopen(@TempDir Path dir) throws Exception {
		Path db = dir.resolve("world/danteai/memory.db");
		try (SqliteMemoryStore store = new SqliteMemoryStore(db)) {
			exercise(store);
			UUID p = UUID.randomUUID();
			store.touchPlayer(p, "Persistente", 1);
			store.addFact(p, "fact", "dato", 3, 1);
		}
		try (SqliteMemoryStore reopened = new SqliteMemoryStore(db)) {
			assertEquals("Persistente", reopened.knownPlayers(5).getFirst().name());
			assertEquals("dato", reopened.facts(reopened.knownPlayers(5).getFirst().player(), 5).getFirst().content());
		}
	}

	@Test
	void inMemoryStoreBehavesTheSame() {
		exercise(new InMemoryMemoryStore());
	}

	@Test
	void serviceLoadsContextAndSummarizesOldMessages(@TempDir Path dir) throws Exception {
		MemoryStore store = MemoryService.openStore(dir.resolve("m.db"), true);
		assertTrue(store.persistent());
		MemoryService service = new MemoryService(store, new MemoryService.Settings(true, 4, 10, 5, 30));
		service.setSummarizer(text -> CompletableFuture.completedFuture("RESUMEN(" + text.length() + ")"));
		UUID p = UUID.randomUUID();
		service.loadContext(p, "Alejo").get(5, TimeUnit.SECONDS);
		for (int i = 0; i < 7; i++) service.recordExchange(p, "pregunta " + i, "respuesta " + i);
		service.remember(p, "preference", "Ama los huesos", 3).get(5, TimeUnit.SECONDS);

		MemoryService.Context ctx = null;
		for (int attempt = 0; attempt < 50; attempt++) {
			ctx = service.loadContext(p, "Alejo").get(5, TimeUnit.SECONDS);
			if (ctx.profile().summary().startsWith("RESUMEN")) break;
			Thread.sleep(50);
		}
		assertTrue(ctx.profile().summary().startsWith("RESUMEN"), "se resumió el historial viejo");
		assertTrue(ctx.recent().size() <= 4, "contexto limitado: " + ctx.recent().size());
		assertEquals("Ama los huesos", ctx.playerFacts().getFirst().content());
		assertEquals("respuesta 6", ctx.recent().getLast().content());
		service.close();
	}

	@Test
	void disabledMemoryReturnsEmptyContext() throws Exception {
		MemoryService service = new MemoryService(new InMemoryMemoryStore(), new MemoryService.Settings(false, 4, 10, 5, 30));
		MemoryService.Context ctx = service.loadContext(UUID.randomUUID(), "x").get(1, TimeUnit.SECONDS);
		assertNull(ctx.profile());
		assertTrue(ctx.recent().isEmpty());
		service.close();
	}
}
