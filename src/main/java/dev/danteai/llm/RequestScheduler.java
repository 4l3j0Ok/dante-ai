package dev.danteai.llm;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Limita la concurrencia, la cola y la tasa global de solicitudes salientes (LLM o voz).
 * Las tareas que no entran se rechazan inmediatamente con {@link RejectedException} para que el juego
 * pueda responder con un comportamiento alternativo en lugar de acumular trabajo.
 */
public final class RequestScheduler {
	public static final class RejectedException extends RuntimeException {
		private final String reason;

		public RejectedException(String reason) {
			super("Solicitud rechazada: " + reason, null, false, false);
			this.reason = reason;
		}

		public String reason() {
			return reason;
		}
	}

	private record Pending<T>(Supplier<CompletableFuture<T>> task, CompletableFuture<T> result) {
	}

	private final int maxConcurrent;
	private final int maxQueue;
	private final int perMinute;
	private final LongSupplier clock;
	private final Deque<Pending<?>> queue = new ArrayDeque<>();
	private final Deque<Long> startTimes = new ArrayDeque<>();
	private final List<CompletableFuture<?>> running = new ArrayList<>();
	private int active;

	public RequestScheduler(int maxConcurrent, int maxQueue, int perMinute, LongSupplier clockMillis) {
		this.maxConcurrent = Math.max(1, maxConcurrent);
		this.maxQueue = Math.max(0, maxQueue);
		this.perMinute = Math.max(1, perMinute);
		this.clock = clockMillis;
	}

	public RequestScheduler(int maxConcurrent, int maxQueue, int perMinute) {
		this(maxConcurrent, maxQueue, perMinute, System::currentTimeMillis);
	}

	public synchronized <T> CompletableFuture<T> submit(Supplier<CompletableFuture<T>> task) {
		long now = clock.getAsLong();
		while (!startTimes.isEmpty() && now - startTimes.peekFirst() >= 60_000) {
			startTimes.pollFirst();
		}
		if (startTimes.size() + queue.size() >= perMinute) {
			return CompletableFuture.failedFuture(new RejectedException("rate_limit"));
		}
		CompletableFuture<T> result = new CompletableFuture<>();
		if (active < maxConcurrent) {
			start(new Pending<>(task, result));
		} else if (queue.size() < maxQueue) {
			queue.addLast(new Pending<>(task, result));
		} else {
			return CompletableFuture.failedFuture(new RejectedException("busy"));
		}
		return result;
	}

	private <T> void start(Pending<T> pending) {
		active++;
		startTimes.addLast(clock.getAsLong());
		CompletableFuture<T> inner;
		try {
			inner = pending.task.get();
		} catch (RuntimeException e) {
			inner = CompletableFuture.failedFuture(e);
		}
		CompletableFuture<T> started = inner;
		running.add(started);
		pending.result.whenComplete((r, e) -> {
			if (pending.result.isCancelled()) started.cancel(true);
		});
		started.whenComplete((value, error) -> {
			synchronized (this) {
				running.remove(started);
				active--;
				Pending<?> next = queue.pollFirst();
				while (next != null && next.result.isDone()) {
					next = queue.pollFirst();
				}
				if (next != null) start(next);
			}
			if (error != null) pending.result.completeExceptionally(HttpSupport.unwrap(error));
			else pending.result.complete(value);
		});
	}

	/** Cancela todo lo pendiente y en curso (por ejemplo al detener el servidor o recargar configuración). */
	public void cancelAll() {
		List<CompletableFuture<?>> toCancel;
		List<Pending<?>> queued;
		synchronized (this) {
			toCancel = new ArrayList<>(running);
			queued = new ArrayList<>(queue);
			queue.clear();
		}
		for (Pending<?> p : queued) p.result.completeExceptionally(new CancellationException("cancelado"));
		for (CompletableFuture<?> f : toCancel) f.cancel(true);
	}

	public synchronized int active() {
		return active;
	}

	public synchronized int queued() {
		return queue.size();
	}
}
