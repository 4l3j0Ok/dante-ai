package dev.danteai.test.chat;

import dev.danteai.chat.DirectCommandParser;
import dev.danteai.chat.DirectCommandParser.Command;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DirectCommandParserTest {
	private static final List<String> NAMES = List.of("Dante");

	private static Command parse(String text) {
		return DirectCommandParser.parse(text, NAMES);
	}

	@Test
	void recognizesClearMovementCommands() {
		assertEquals(Command.FOLLOW, parse("seguime"));
		assertEquals(Command.FOLLOW, parse("Dante, seguime por favor"));
		assertEquals(Command.FOLLOW, parse("vení conmigo, mi rey"));
		assertEquals(Command.STOP_FOLLOW, parse("dejá de seguirme"));
		assertEquals(Command.STOP_FOLLOW, parse("pará de seguirme"));
		assertEquals(Command.STOP_FOLLOW, parse("no me sigas más"));
		assertEquals(Command.SIT, parse("sentate"));
		assertEquals(Command.SIT, parse("quedate acá"));
		assertEquals(Command.STAND, parse("parate"));
		assertEquals(Command.COME, parse("vení"));
		assertEquals(Command.COME, parse("che Dante venite"));
		assertEquals(Command.WANDER, parse("andá a pasear"));
	}

	@Test
	void silenceCommandsAreRecognized() {
		assertEquals(Command.SILENCE, parse("callate"));
		assertEquals(Command.SILENCE, parse("Dante, silencio"));
		assertEquals(Command.SILENCE, parse("pará"));
		assertEquals(Command.SILENCE, parse("no hables más"));
	}

	@Test
	void negationWinsOverFollow() {
		assertEquals(Command.STOP_FOLLOW, parse("no me sigas"));
		assertNull(parse("no seguime"));
	}

	@Test
	void ambiguousOrLongMessagesGoToTheModel() {
		assertNull(parse("¿me seguís?"));
		assertNull(parse("vamos a ver qué pasa"));
		assertNull(parse("seguí contando ese chiste"));
		assertNull(parse("seguime y después contame un chiste largo"));
		assertNull(parse("sentate a pensar en tus pecados"));
		assertNull(parse("devolveme mis diamantes"));
		assertNull(parse("hola"));
		assertNull(parse(""));
	}
}
