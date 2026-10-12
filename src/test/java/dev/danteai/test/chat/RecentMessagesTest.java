package dev.danteai.test.chat;

import dev.danteai.chat.RecentMessages;
import dev.danteai.chat.TextUtil;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecentMessagesTest {
	private static final UUID ANA = UUID.randomUUID();
	private static final UUID LUIS = UUID.randomUUID();

	@Test
	void suppressesTheSameMessageIgnoringFormatWithinTheWindow() {
		RecentMessages recent = new RecentMessages(10_000);
		assertFalse(recent.isDuplicate(ANA, "¡Dale, mi rey! *mueve la cola*", 0));
		assertTrue(recent.isDuplicate(ANA, "dale mi rey mueve la cola", 2_000));
	}

	@Test
	void allowsTheSameMessageToAnotherPersonOrAfterTheWindow() {
		RecentMessages recent = new RecentMessages(10_000);
		assertFalse(recent.isDuplicate(ANA, "Hola, mi reina, qué lindo día para pasear", 0));
		assertFalse(recent.isDuplicate(LUIS, "Hola, mi reina, qué lindo día para pasear", 1_000));
		assertFalse(recent.isDuplicate(ANA, "Hola, mi reina, qué lindo día para pasear", 11_000));
	}

	@Test
	void differentMessagesAreNeverSuppressed() {
		RecentMessages recent = new RecentMessages(10_000);
		assertFalse(recent.isDuplicate(ANA, "Voy para allá, mi rey", 0));
		assertFalse(recent.isDuplicate(ANA, "Ya llegué, mi rey", 500));
	}

	@Test
	void longMessageContainingAnEarlierOneCountsAsTheSame() {
		assertTrue(TextUtil.sameMessage("Ahí voy, mi rey, servicio a domicilio", "Ahí voy, mi rey, servicio a domicilio. Prepará las galletitas."));
		assertFalse(TextUtil.sameMessage("Sí", "No"));
	}
}
