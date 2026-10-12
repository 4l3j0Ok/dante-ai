package dev.danteai.test.llm;

import dev.danteai.chat.PlayerRateLimiter;
import dev.danteai.llm.RequestScheduler;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestSchedulerTest {
	@Test
	void limitsConcurrencyAndQueuesInOrder() {
		AtomicLong clock = new AtomicLong();
		RequestScheduler s = new RequestScheduler(2, 2, 100, clock::get);
		List<CompletableFuture<String>> inner = new ArrayList<>();
		List<CompletableFuture<String>> outer = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			outer.add(s.submit(() -> {
				CompletableFuture<String> f = new CompletableFuture<>();
				inner.add(f);
				return f;
			}));
		}
		assertEquals(2, s.active());
		assertEquals(2, s.queued());
		assertEquals(2, inner.size(), "solo arrancan 2 a la vez");

		// La quinta no entra: cola llena.
		CompletableFuture<String> rejected = s.submit(() -> CompletableFuture.completedFuture("x"));
		CompletionException e = assertThrows(CompletionException.class, rejected::join);
		assertEquals("busy", assertInstanceOf(RequestScheduler.RejectedException.class, e.getCause()).reason());

		inner.get(0).complete("a");
		assertEquals("a", outer.get(0).join());
		assertEquals(3, inner.size(), "al terminar una arranca la siguiente de la cola");
		inner.get(1).complete("b");
		inner.get(2).complete("c");
		inner.get(3).complete("d");
		assertEquals("d", outer.get(3).join());
		assertEquals(0, s.active());
	}

	@Test
	void enforcesRequestsPerMinute() {
		AtomicLong clock = new AtomicLong(1_000_000);
		RequestScheduler s = new RequestScheduler(5, 5, 3, clock::get);
		for (int i = 0; i < 3; i++) s.submit(() -> CompletableFuture.completedFuture("ok")).join();
		CompletionException e = assertThrows(CompletionException.class, () -> s.submit(() -> CompletableFuture.completedFuture("x")).join());
		assertEquals("rate_limit", ((RequestScheduler.RejectedException) e.getCause()).reason());
		clock.addAndGet(61_000);
		assertEquals("ok", s.submit(() -> CompletableFuture.completedFuture("ok")).join());
	}

	@Test
	void cancelAllCancelsRunningAndQueued() {
		RequestScheduler s = new RequestScheduler(1, 3, 100);
		CompletableFuture<String> running = new CompletableFuture<>();
		CompletableFuture<String> a = s.submit(() -> running);
		CompletableFuture<String> b = s.submit(() -> new CompletableFuture<>());
		s.cancelAll();
		assertTrue(running.isCancelled());
		assertTrue(a.isCompletedExceptionally());
		assertTrue(b.isCompletedExceptionally());
	}

	@Test
	void cancellingResultCancelsUnderlyingRequest() {
		RequestScheduler s = new RequestScheduler(1, 1, 100);
		CompletableFuture<String> underlying = new CompletableFuture<>();
		CompletableFuture<String> result = s.submit(() -> underlying);
		result.cancel(true);
		assertTrue(underlying.isCancelled());
	}

	@Test
	void playerRateLimiterAppliesCooldownAndHourlyQuota() {
		AtomicLong clock = new AtomicLong(0);
		PlayerRateLimiter limiter = new PlayerRateLimiter(3000, 3, clock::get);
		UUID p = UUID.randomUUID();
		assertEquals(PlayerRateLimiter.Decision.ALLOWED, limiter.tryAcquire(p));
		assertEquals(PlayerRateLimiter.Decision.COOLDOWN, limiter.tryAcquire(p));
		clock.addAndGet(3000);
		assertEquals(PlayerRateLimiter.Decision.ALLOWED, limiter.tryAcquire(p));
		clock.addAndGet(3000);
		assertEquals(PlayerRateLimiter.Decision.ALLOWED, limiter.tryAcquire(p));
		clock.addAndGet(3000);
		assertEquals(PlayerRateLimiter.Decision.HOURLY_LIMIT, limiter.tryAcquire(p));
		assertEquals(PlayerRateLimiter.Decision.ALLOWED, limiter.tryAcquire(UUID.randomUUID()), "otros jugadores no se ven afectados");
		clock.addAndGet(3_600_000);
		assertEquals(PlayerRateLimiter.Decision.ALLOWED, limiter.tryAcquire(p));
	}
}
