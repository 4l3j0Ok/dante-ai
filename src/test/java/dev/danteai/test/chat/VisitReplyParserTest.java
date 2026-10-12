package dev.danteai.test.chat;

import dev.danteai.behavior.VisitScheduler.Reply;
import dev.danteai.chat.VisitReplyParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class VisitReplyParserTest {
	private static final List<String> NAMES = List.of("Dante");

	private static Reply parse(String text, boolean loose) {
		return VisitReplyParser.parse(text, NAMES, loose);
	}

	@Test
	void clearStayPhrasesWorkWithoutMentioningDante() {
		assertEquals(Reply.STAY, parse("quedate", false));
		assertEquals(Reply.STAY, parse("no te vayas", false));
		assertEquals(Reply.STAY, parse("No te vayas todavía", false));
		assertEquals(Reply.STAY, parse("Dante, quedate un rato mi rey", false));
	}

	@Test
	void clearGoPhrasesWorkWithoutMentioningDante() {
		assertEquals(Reply.GO, parse("andá", false));
		assertEquals(Reply.GO, parse("andate nomás", false));
		assertEquals(Reply.GO, parse("Dante, andá a visitarlo", false));
	}

	@Test
	void ambiguousWordsOnlyCountWhenAddressedToHim() {
		assertNull(parse("dale", false));
		assertNull(parse("sí", false));
		assertNull(parse("no", false));
		assertEquals(Reply.GO, parse("dale", true));
		assertEquals(Reply.GO, parse("chau Dante", true));
		assertEquals(Reply.STAY, parse("no", true));
		assertEquals(Reply.STAY, parse("todavía no", true));
	}

	@Test
	void negationsStayAndOrdinaryChatIsIgnored() {
		assertEquals(Reply.STAY, parse("no te vayas", true));
		assertNull(parse("¿te vas?", true));
		assertNull(parse("hoy construyo una casa enorme", true));
		assertNull(parse("jaja qué bueno", true));
	}
}
