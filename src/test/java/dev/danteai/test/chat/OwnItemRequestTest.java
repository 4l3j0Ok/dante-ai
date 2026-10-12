package dev.danteai.test.chat;

import dev.danteai.chat.OwnItemRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OwnItemRequestTest {
	@Test
	void pronounOnlyRequestHasNoItemWords() {
		assertEquals(List.of(), OwnItemRequest.meaningfulWords("Devolvemela", "Alejo"));
	}

	@Test
	void extractsTheObjectAndIgnoresGreetingsAndName() {
		assertEquals(List.of("espada"), OwnItemRequest.meaningfulWords("Alejo, dame mi espada", "Alejo"));
		assertEquals(List.of("hacha"), OwnItemRequest.meaningfulWords("Dante devolveme el hacha porfa", "Alejo"));
	}

	@Test
	void recognizesAllRequests() {
		assertEquals(List.of("todo"), OwnItemRequest.meaningfulWords("devolveme todo", "Alejo"));
	}

	@Test
	void readsTheRequestedQuantity() {
		assertEquals(3, OwnItemRequest.quantity("devolveme 3 diamantes"));
		assertEquals(0, OwnItemRequest.quantity("devolveme la espada"));
	}
}
