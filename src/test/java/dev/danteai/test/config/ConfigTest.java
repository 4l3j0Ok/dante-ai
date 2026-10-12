package dev.danteai.test.config;

import dev.danteai.config.ConfigIO;
import dev.danteai.config.ConfigValidator;
import dev.danteai.config.DanteConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigTest {
	@Test
	void writesDocumentedFileAndReadsItBack(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("config.jsonc");
		DanteConfig created = ConfigIO.loadOrCreate(file);
		String text = Files.readString(file);
		assertTrue(text.contains("// Proveedor"), "el archivo documenta las opciones");
		assertTrue(text.contains("\"teleport_distance\": 80"));
		DanteConfig reread = ConfigIO.parse(text);
		assertEquals(created.llm.provider, reread.llm.provider);
		assertEquals(created.behavior.follow_probability, reread.behavior.follow_probability);
		assertEquals(created.voice.openai.voice, reread.voice.openai.voice);
	}

	@Test
	void acceptsCommentsAndFillsMissingKeysWithDefaults() throws Exception {
		DanteConfig c = ConfigIO.parse("""
			// comentario de línea
			{
			  /* bloque */
			  "llm": { "provider": "anthropic", "anthropic": { "model": "claude-sonnet-5-5" } },
			  "behavior": { "teleport_distance": 120 }
			}""");
		ConfigValidator.validate(c);
		assertEquals("anthropic", c.llm.provider);
		assertEquals("claude-sonnet-5-5", c.llm.anthropic.model);
		assertEquals("https://api.anthropic.com", c.llm.anthropic.base_url, "lo no especificado toma el valor por defecto");
		assertEquals(120, c.behavior.teleport_distance);
		assertEquals(0.25, c.behavior.follow_probability);
		assertEquals("http://localhost:1234/v1", c.llm.openai.base_url);
	}

	@Test
	void clampsInvalidValues() throws Exception {
		DanteConfig c = ConfigIO.parse("""
			{"behavior": {"follow_probability": 7, "teleport_distance": -5, "follow_min_seconds": 100, "follow_max_seconds": 10},
			 "llm": {"provider": "openia", "max_retries": 99, "openai": {"tools_mode": "raro"}},
			 "voice": {"pitch": 50},
			 "inventory": {"slots": 0},
			 "dante": {"name": "   "}}""");
		var warnings = ConfigValidator.validate(c);
		assertEquals(1.0, c.behavior.follow_probability);
		assertEquals(16, c.behavior.teleport_distance);
		assertTrue(c.behavior.follow_max_seconds >= c.behavior.follow_min_seconds);
		assertEquals("openai", c.llm.provider);
		assertTrue(warnings.stream().anyMatch(w -> w.contains("openia")));
		assertEquals(5, c.llm.max_retries);
		assertEquals("native", c.llm.openai.tools_mode);
		assertEquals(2.0, c.voice.pitch);
		assertEquals(1, c.inventory.slots);
		assertEquals("Dante", c.dante.name);
	}

	@Test
	void oldConfigsWithRemovedOptionsStillLoad() throws Exception {
		DanteConfig c = ConfigIO.parse("""
			{"llm": {"provider": "opencode", "opencode": {"base_url": "http://127.0.0.1:49374", "model": "x/y"},
			         "openai": {"base_url": "http://192.168.0.34:1234/v1", "model": "m"}},
			 "voice": {"provider": "elevenlabs", "elevenlabs": {"api_key": "k", "voice_id": "v"},
			           "openai": {"base_url": "http://192.168.0.34:28880/v1", "model": "kokoro", "voice": "em_alex",
			                      "response_format": "pcm", "pcm_sample_rate": 22050}}}""");
		var warnings = ConfigValidator.validate(c);
		assertEquals("openai", c.llm.provider, "un proveedor eliminado vuelve a openai");
		assertTrue(warnings.stream().anyMatch(w -> w.contains("opencode")));
		assertEquals("http://192.168.0.34:1234/v1", c.llm.openai.base_url);
		assertEquals("http://192.168.0.34:28880/v1", c.voice.openai.base_url);
		assertEquals("em_alex", c.voice.openai.voice);
	}

	@Test
	void defaultsPointToTheCurrentLocalServices() {
		DanteConfig c = new DanteConfig();
		assertEquals("http://127.0.0.1:28880/v1", c.voice.openai.base_url);
		assertEquals("kokoro", c.voice.openai.model);
		assertEquals("em_alex", c.voice.openai.voice);
		assertEquals("http://127.0.0.1:28000/v1", c.voice.listen.base_url);
	}

	@Test
	void nullSectionsAreRepaired() throws Exception {
		DanteConfig c = ConfigIO.parse("{\"llm\": null, \"voice\": {\"listen\": null}}");
		ConfigValidator.validate(c);
		assertEquals("openai", c.llm.provider);
		assertEquals("Systran/faster-whisper-small", c.voice.listen.model);
	}
}
