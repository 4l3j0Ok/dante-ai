package dev.danteai.test.chat;

import dev.danteai.chat.FallbackIntentParser;
import dev.danteai.chat.FallbackIntentParser.Type;
import dev.danteai.chat.MentionDetector;
import dev.danteai.chat.TextUtil;
import dev.danteai.llm.JsonToolProtocol;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatParsingTest {
	private final MentionDetector detector = new MentionDetector(List.of("Dante"));

	@Test
	void detectsMentions() {
		var r = detector.detect("@Dante ¿qué estás haciendo?");
		assertTrue(r.addressed());
		assertEquals("¿qué estás haciendo?", r.cleanedMessage());

		r = detector.detect("Dante, guardame estos diamantes.");
		assertTrue(r.addressed());
		assertEquals("guardame estos diamantes.", r.cleanedMessage());

		assertTrue(detector.detect("dante vení conmigo").addressed());
		assertTrue(detector.detect("Ey Dante! vení").addressed());
		assertTrue(detector.detect("che @DANTE, mirá esto").addressed());
		assertTrue(detector.detect("Dánte, hola").addressed(), "tolera acentos");

		assertFalse(detector.detect("le dije a Juan que Dante es lindo").addressed(), "mencionarlo al pasar no lo activa");
		assertFalse(detector.detect("Dantesco paisaje").addressed());
		assertFalse(detector.detect("hola a todos").addressed());
	}

	@Test
	void detectsNameInVoiceTranscripts() {
		assertTrue(detector.containsName("oye dante ven aquí", List.of("Dante")));
		assertFalse(detector.containsName("vamos a la mina", List.of("Dante")));
	}

	@Test
	void parsesFallbackIntents() {
		assertEquals(Type.FOLLOW, FallbackIntentParser.parse("vení conmigo").type());
		assertEquals(Type.FOLLOW, FallbackIntentParser.parse("follow me").type());
		assertEquals(Type.STOP_FOLLOW, FallbackIntentParser.parse("dejá de seguirme").type());
		assertEquals(Type.SIT, FallbackIntentParser.parse("sentate").type());
		assertEquals(Type.SIT, FallbackIntentParser.parse("quedate acá").type());
		assertEquals(Type.SILENCE, FallbackIntentParser.parse("callate un rato").type());
		assertEquals(Type.UNKNOWN, FallbackIntentParser.parse("vamos a la mina").type());
		assertEquals(Type.INVENTORY, FallbackIntentParser.parse("¿qué tenés encima?").type());
		assertEquals(Type.RETURN_ALL, FallbackIntentParser.parse("devolveme todo").type());
		var give = FallbackIntentParser.parse("devolveme mis diamantes");
		assertEquals(Type.GIVE_ITEM, give.type());
		assertEquals("diamantes", give.itemHint());
		var take = FallbackIntentParser.parse("guardame estos diamantes");
		assertEquals(Type.TAKE_HELD, take.type());
		assertEquals("diamantes", take.itemHint());
		assertEquals(Type.PICKUP, FallbackIntentParser.parse("recogé ese objeto").type());
		assertEquals(Type.GREETING, FallbackIntentParser.parse("¿qué estás haciendo?").type());
		assertEquals(Type.UNKNOWN, FallbackIntentParser.parse("la entropía del universo").type());
	}

	@Test
	void sanitizesModelOutput() {
		assertEquals("¡Hola! mueve la cola", TextUtil.sanitizeForChat("Dante: \"¡Hola!\n\n**mueve la cola**\"", "Dante"));
		assertEquals("¡Hola! *mueve la cola*", TextUtil.sanitizeForChat("¡Hola! *mueve la cola*", "Dante"));
		assertEquals("texto limpio", TextUtil.sanitizeForChat("<think>razono</think>§ctexto   limpio", "Dante"));
		assertEquals("rojo", TextUtil.sanitizeForChat("§4rojo", "Dante"));
	}

	@Test
	void splitsLongMessagesOnWordBoundaries() {
		String text = "palabra ".repeat(60).strip();
		List<String> parts = TextUtil.split(text, 100, 10);
		assertTrue(parts.size() >= 4);
		for (String p : parts) {
			assertTrue(p.length() <= 100);
			assertFalse(p.startsWith(" ") || p.endsWith(" "));
		}
		List<String> limited = TextUtil.split(text, 50, 2);
		assertEquals(2, limited.size());
		assertTrue(limited.getLast().endsWith("…"));
	}

	@Test
	void speakableTextDropsActionsAndEmojis() {
		assertEquals("¡Hola Alejo! ¿Jugamos?", TextUtil.speakable("¡Hola Alejo! *mueve la cola* ¿Jugamos? 🐶"));
		assertEquals("", TextUtil.speakable("*ladra*"));
	}

	@Test
	void speakableTextDropsLongActionsAndStrayAsterisks() {
		String longAction = "*Dejo de mover la cola por un instante y miro hacia el horizonte, como si estuviera mirando el punto exacto donde Julián fue expulsado.*";
		assertEquals("¡Julián! Mi rey, ese pibe es una pasada.",
			TextUtil.speakable(longAction + " ¡Julián! *Suena mi voz con un tono más grave, casi solemne*. Mi rey, ese pibe es una pasada."));
		assertEquals("se acerca Dale, mi rey.", TextUtil.speakable("*se acerca Dale, mi rey."), "una acción sin cerrar solo pierde el asterisco");
		assertEquals("", TextUtil.speakable(longAction));
	}

	@Test
	void speechIsCutAtTheEndOfASentenceWithoutEllipsis() {
		String text = "Primera frase completa. Segunda frase también completa. Tercera frase que ya no entra del todo.";
		assertEquals("Primera frase completa. Segunda frase también completa.", TextUtil.truncateForSpeech(text, 70));
		assertEquals(text, TextUtil.truncateForSpeech(text, 500), "si entra, no se toca");
		String noPunctuation = "una frase larga sin ningún signo de puntuación que sigue y sigue y sigue";
		String cut = TextUtil.truncateForSpeech(noPunctuation, 40);
		assertTrue(cut.length() <= 40 && !cut.endsWith("…") && noPunctuation.startsWith(cut), cut);
		assertEquals("una frase larga sin ningún signo de", cut);
	}

	@Test
	void jsonToolProtocolToleratesNoise() {
		var parsed = JsonToolProtocol.parse("Claro! {\"say\": \"Voy {ya}\", \"tool_calls\": [{\"name\": \"follow_player\", \"arguments\": {\"player\": \"Ana\"}}]} fin");
		assertEquals("Voy {ya}", parsed.say());
		assertEquals("Ana", parsed.toolCalls().getFirst().arguments().get("player").getAsString());
		var plain = JsonToolProtocol.parse("Solo texto, sin JSON");
		assertEquals("Solo texto, sin JSON", plain.say());
		assertTrue(plain.toolCalls().isEmpty());
	}
}
