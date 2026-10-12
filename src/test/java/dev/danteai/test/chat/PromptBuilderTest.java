package dev.danteai.test.chat;

import dev.danteai.chat.PromptBuilder;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class PromptBuilderTest {
	@Test
	void keepsPersonalityAndVoiceContextWithoutOverridingItsLanguage() {
		String personality = "Hablás en argentino y usás mi reina.";
		String prompt = PromptBuilder.build(new PromptBuilder.Input(
			personality, "Dante", "Ana", "Estás descansando.\n", null, true, 4000));
		assertTrue(prompt.startsWith(personality));
		assertTrue(prompt.contains("Ana (por voz"));
		assertTrue(prompt.contains("Respetá el idioma, el tono y los apelativos de tu personalidad"));
		assertFalse(prompt.contains("Respondé en el idioma del jugador"));
		assertTrue(prompt.contains("Nunca digas que hiciste algo si la herramienta falló o no la usaste"));
	}

	@Test
	void packagedPersonalityIncludesAffectionateTeasingAndPlayerBoundaries() throws IOException {
		try (var in = getClass().getResourceAsStream("/danteai/system_prompt.md")) {
			assertNotNull(in);
			String personality = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			assertTrue(personality.contains("sarcástico"));
			assertTrue(personality.contains("argentino rioplatense"));
			assertTrue(personality.contains("mi rey"));
			assertTrue(personality.contains("mi reina"));
			assertTrue(personality.contains("Si te corrigen, respetá la preferencia"));
			assertTrue(personality.contains("Si el jugador te pide que pares"));
			assertTrue(personality.contains("No robás, escondés ni tirás objetos"));
		}
	}
}
