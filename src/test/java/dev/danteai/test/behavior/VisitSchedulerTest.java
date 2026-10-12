package dev.danteai.test.behavior;

import dev.danteai.behavior.VisitScheduler;
import dev.danteai.behavior.VisitScheduler.Action;
import dev.danteai.behavior.VisitScheduler.Reply;
import dev.danteai.behavior.VisitScheduler.Type;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisitSchedulerTest {
	private static final long AFTER = 30 * 60;
	private static final long REPLY = 30;
	private static final long STAY = 30 * 60;
	private static final UUID ANA = UUID.randomUUID();
	private static final UUID LUIS = UUID.randomUUID();

	private final VisitScheduler s = new VisitScheduler(AFTER, REPLY, STAY);

	/** Simula un tick por segundo con compañía hasta {@code until}; devuelve la primera acción que no sea NONE. */
	private Action run(long from, long until, boolean company, List<UUID> candidates) {
		for (long t = from; t <= until; t++) {
			Action a = s.tick(t, company, candidates);
			if (a.type() != Type.NONE) return a;
		}
		return new Action(Type.NONE, null);
	}

	@Test
	void doesNothingBeforeThirtyMinutes() {
		assertEquals(Type.NONE, run(0, AFTER - 1, true, List.of(ANA)).type());
	}

	@Test
	void announcesAfterThirtyMinutesWhenSomeoneElseIsOnline() {
		Action a = run(0, AFTER + 5, true, List.of(ANA));
		assertEquals(Type.ANNOUNCE, a.type());
		assertEquals(ANA, a.target());
		assertTrue(s.pending());
	}

	@Test
	void neverAnnouncesIfNobodyElseIsAvailable() {
		assertEquals(Type.NONE, run(0, AFTER * 3, true, List.of()).type());
		assertFalse(s.pending());
	}

	@Test
	void announcesAsSoonAsSomeoneElseAppearsAfterTheTime() {
		assertEquals(Type.NONE, run(0, AFTER + 100, true, List.of()).type());
		Action a = s.tick(AFTER + 101, true, List.of(LUIS));
		assertEquals(Type.ANNOUNCE, a.type());
		assertEquals(LUIS, a.target());
	}

	@Test
	void leavesAfterThirtySecondsWithoutAnswer() {
		run(0, AFTER, true, List.of(ANA));
		assertEquals(Type.NONE, s.tick(AFTER + REPLY - 1, true, List.of(ANA)).type());
		Action leave = s.tick(AFTER + REPLY, true, List.of(ANA));
		assertEquals(Type.LEAVE, leave.type());
		assertEquals(ANA, leave.target());
		assertFalse(s.pending());
	}

	@Test
	void confirmationMakesHimLeaveImmediately() {
		run(0, AFTER, true, List.of(ANA));
		Action a = s.reply(AFTER + 3, Reply.GO);
		assertEquals(Type.LEAVE, a.type());
		assertEquals(ANA, a.target());
		assertFalse(s.pending());
	}

	@Test
	void askingHimToStayDelaysTheNextVisitByThirtyMinutes() {
		run(0, AFTER, true, List.of(ANA));
		long answered = AFTER + 5;
		assertEquals(Type.STAY, s.reply(answered, Reply.STAY).type());
		assertEquals(Type.NONE, run(answered + 1, answered + STAY - 1, true, List.of(ANA)).type());
		assertEquals(Type.ANNOUNCE, s.tick(answered + STAY, true, List.of(ANA)).type());
	}

	@Test
	void repliesWithoutAPendingNoticeAreIgnored() {
		assertEquals(Type.NONE, s.reply(10, Reply.GO).type());
		assertEquals(Type.NONE, s.reply(10, Reply.STAY).type());
	}

	@Test
	void cancelsIfTheTargetDisconnectsOrArrives() {
		run(0, AFTER, true, List.of(ANA));
		Action a = s.tick(AFTER + 2, true, List.of(LUIS));
		assertEquals(Type.CANCEL, a.type());
		assertEquals(ANA, a.target());
		assertFalse(s.pending());
	}

	@Test
	void briefAbsenceDoesNotResetTheClock() {
		run(0, 600, true, List.of(ANA));
		assertEquals(Type.NONE, run(601, 700, false, List.of(ANA)).type());
		assertEquals(Type.ANNOUNCE, s.tick(AFTER, true, List.of(ANA)).type());
	}

	@Test
	void timerRestartsWhenCompanyIsLostForLong() {
		run(0, 600, true, List.of(ANA));
		assertEquals(Type.NONE, run(601, 1300, false, List.of(ANA)).type());
		assertEquals(Type.NONE, run(1301, 1301 + AFTER - 2, true, List.of(ANA)).type());
		assertEquals(Type.ANNOUNCE, s.tick(1301 + AFTER, true, List.of(ANA)).type());
	}

	@Test
	void visitsTheLeastRecentlyVisitedPlayerFirst() {
		run(0, AFTER, true, List.of(ANA, LUIS));
		s.reply(AFTER + 1, Reply.GO);
		long later = AFTER + 10;
		Action a = run(later, later + AFTER + 10, true, List.of(ANA, LUIS));
		assertEquals(Type.ANNOUNCE, a.type());
		assertEquals(LUIS, a.target());
	}
}
