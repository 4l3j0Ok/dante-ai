package dev.danteai.voice;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * Cola de reproducción de voz: los mensajes suenan en el orden en que se pidieron y cada uno espera a que termine
 * el anterior (nunca lo corta). Hay un máximo de mensajes esperando; si se supera, se descartan los más viejos.
 */
public final class VoiceQueue {
	/** Inicia la reproducción y devuelve un futuro que termina cuando el audio acabó; {@code null} si no pudo empezar. */
	public interface Starter {
		CompletableFuture<Boolean> start(List<short[]> frames);
	}

	public static final class Ticket {
		private final CompletableFuture<Boolean> result = new CompletableFuture<>();
		private List<short[]> frames;
		private boolean done;
		private boolean failed;

		/** Se completa con {@code true} cuando el audio empieza a sonar, o {@code false} si se descartó o falló. */
		public CompletableFuture<Boolean> result() {
			return result;
		}
	}

	private final Object lock = new Object();
	private final ArrayDeque<Ticket> queue = new ArrayDeque<>();
	private final int maxQueued;
	private final Executor pumpExecutor;
	private final Starter starter;
	private boolean playing;

	/**
	 * @param pumpExecutor hilo desde el que se inicia cada reproducción (el del servidor de Minecraft)
	 */
	public VoiceQueue(int maxQueued, Executor pumpExecutor, Starter starter) {
		this.maxQueued = Math.max(1, maxQueued);
		this.pumpExecutor = pumpExecutor;
		this.starter = starter;
	}

	/** Reserva un lugar en la cola. Si ya hay {@code maxQueued} esperando, descarta el más viejo. */
	public Ticket enqueue() {
		Ticket ticket = new Ticket();
		List<Ticket> dropped = new ArrayList<>();
		synchronized (lock) {
			while (queue.size() >= maxQueued) dropped.add(queue.pollFirst());
			queue.addLast(ticket);
		}
		for (Ticket t : dropped) t.result.complete(false);
		return ticket;
	}

	public void ready(Ticket ticket, List<short[]> frames) {
		synchronized (lock) {
			ticket.frames = frames;
			ticket.done = true;
		}
		pumpExecutor.execute(this::pump);
	}

	public void failed(Ticket ticket) {
		synchronized (lock) {
			ticket.failed = true;
			ticket.done = true;
		}
		pumpExecutor.execute(this::pump);
	}

	/** Descarta todo lo que esté esperando (no corta el audio que ya está sonando). */
	public void clear() {
		List<Ticket> dropped;
		synchronized (lock) {
			dropped = new ArrayList<>(queue);
			queue.clear();
		}
		for (Ticket t : dropped) t.result.complete(false);
	}

	public int waiting() {
		synchronized (lock) {
			return queue.size();
		}
	}

	public boolean playing() {
		synchronized (lock) {
			return playing;
		}
	}

	private void pump() {
		while (true) {
			Ticket next;
			synchronized (lock) {
				if (playing) return;
				Ticket head = queue.peekFirst();
				if (head == null || !head.done) return;
				queue.pollFirst();
				if (head.failed) {
					head.result.complete(false);
					continue;
				}
				next = head;
				playing = true;
			}
			CompletableFuture<Boolean> finished;
			try {
				finished = starter.start(next.frames);
			} catch (RuntimeException e) {
				finished = null;
			}
			if (finished == null) {
				synchronized (lock) {
					playing = false;
				}
				next.result.complete(false);
				continue;
			}
			next.result.complete(true);
			long timeoutMs = next.frames.size() * 20L + 3000L;
			finished.orTimeout(timeoutMs, TimeUnit.MILLISECONDS).whenComplete((ok, error) -> {
				synchronized (lock) {
					playing = false;
				}
				pumpExecutor.execute(this::pump);
			});
			return;
		}
	}
}
