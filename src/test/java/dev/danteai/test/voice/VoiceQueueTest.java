package dev.danteai.test.voice;

import dev.danteai.voice.VoiceQueue;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoiceQueueTest {
	private final List<Integer> started = new ArrayList<>();
	private final List<CompletableFuture<Boolean>> playbacks = new ArrayList<>();

	private VoiceQueue queue(int max) {
		return new VoiceQueue(max, Runnable::run, frames -> {
			started.add(frames.size());
			CompletableFuture<Boolean> f = new CompletableFuture<>();
			playbacks.add(f);
			return f;
		});
	}

	private static List<short[]> frames(int n) {
		List<short[]> out = new ArrayList<>();
		for (int i = 0; i < n; i++) out.add(new short[960]);
		return out;
	}

	@Test
	void secondMessageWaitsForTheFirstToFinish() {
		VoiceQueue q = queue(3);
		VoiceQueue.Ticket first = q.enqueue();
		VoiceQueue.Ticket second = q.enqueue();
		q.ready(first, frames(1));
		q.ready(second, frames(2));
		assertEquals(List.of(1), started, "el segundo no empieza mientras suena el primero");
		assertTrue(first.result().isDone());
		assertFalse(second.result().isDone());
		playbacks.get(0).complete(true);
		assertEquals(List.of(1, 2), started);
		assertTrue(second.result().join());
	}

	@Test
	void keepsRequestOrderEvenIfLaterAudioIsReadyFirst() {
		VoiceQueue q = queue(3);
		VoiceQueue.Ticket first = q.enqueue();
		VoiceQueue.Ticket second = q.enqueue();
		q.ready(second, frames(2));
		assertTrue(started.isEmpty(), "espera al primero aunque el segundo esté listo");
		q.ready(first, frames(1));
		assertEquals(List.of(1), started);
		playbacks.get(0).complete(true);
		assertEquals(List.of(1, 2), started);
	}

	@Test
	void failedSynthesisDoesNotBlockTheQueue() {
		VoiceQueue q = queue(3);
		VoiceQueue.Ticket first = q.enqueue();
		VoiceQueue.Ticket second = q.enqueue();
		q.failed(first);
		q.ready(second, frames(2));
		assertFalse(first.result().join());
		assertEquals(List.of(2), started);
	}

	@Test
	void dropsTheOldestWaitingMessagesBeyondTheLimit() {
		VoiceQueue q = queue(3);
		VoiceQueue.Ticket playing = q.enqueue();
		q.ready(playing, frames(1));
		List<VoiceQueue.Ticket> waiting = new ArrayList<>();
		for (int i = 0; i < 4; i++) waiting.add(q.enqueue());
		assertFalse(waiting.get(0).result().join(), "el más viejo se descartó");
		assertEquals(3, q.waiting());
		assertFalse(waiting.get(1).result().isDone());
	}

	@Test
	void clearDropsPendingMessagesWithoutStartingThem() {
		VoiceQueue q = queue(3);
		VoiceQueue.Ticket playing = q.enqueue();
		q.ready(playing, frames(1));
		VoiceQueue.Ticket pending = q.enqueue();
		q.clear();
		assertFalse(pending.result().join());
		q.ready(pending, frames(5));
		playbacks.get(0).complete(true);
		assertEquals(List.of(1), started);
		assertEquals(0, q.waiting());
		assertNotNull(playing);
	}
}
