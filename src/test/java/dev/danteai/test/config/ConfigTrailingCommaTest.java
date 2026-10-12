package dev.danteai.test.config;

import dev.danteai.config.ConfigIO;
import dev.danteai.config.DanteConfig;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigTrailingCommaTest {
	@Test
	void acceptsTrailingCommasAndComments() throws IOException {
		DanteConfig cfg = ConfigIO.parse("""
			// comentario inicial
			{
				"dante": {
					"name": "Dante, el grande",
					"admin_permission_level": 3, // coma final
				},
				"inventory": {
					"autonomous_pickup": ["minecraft:bone", "minecraft:stick",],
					"share_items": false,
				},
			}
			""");
		assertEquals(3, cfg.dante.admin_permission_level);
		assertEquals("Dante, el grande", cfg.dante.name);
		assertEquals(2, cfg.inventory.autonomous_pickup.size());
		assertEquals(false, cfg.inventory.share_items);
	}

	@Test
	void commasInsideStringsAreKept() throws IOException {
		DanteConfig cfg = ConfigIO.parse("{\"dante\": {\"name\": \"a,}\", \"admin_permission_level\": 2}}");
		assertEquals("a,}", cfg.dante.name);
		assertTrue(cfg.dante.admin_permission_level == 2);
	}
}
