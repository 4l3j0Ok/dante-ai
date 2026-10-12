package dev.danteai.test.world;

import dev.danteai.test.McBootstrap;
import dev.danteai.world.DanteWorldData;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DanteWorldDataTest {
	@BeforeAll
	static void bootstrap() {
		McBootstrap.init();
	}

	@Test
	void roundTripsIdentityInventoryOwnershipAndNeeds() {
		var registries = VanillaRegistries.createLookup();
		UUID dante = UUID.randomUUID();
		UUID owner = UUID.randomUUID();
		DanteWorldData original = new DanteWorldData();
		original.setDanteUuid(dante);
		original.setLastPosition("minecraft:the_nether", 20.5, 70, -10);
		original.setNeeds(0.25, 0.4, 0.75);
		original.setRespawnAt(1234);
		original.setRemovedByAdmin(true);
		original.inventory().insert(new ItemStack(Items.DIAMOND, 7), owner, "Alejo");
		CompoundTag tag = original.save(new CompoundTag(), registries);
		DanteWorldData restored = DanteWorldData.FACTORY.deserializer().apply(tag, registries);
		assertEquals(dante, restored.danteUuid());
		assertEquals("minecraft:the_nether", restored.dimension());
		assertEquals(20.5, restored.x());
		assertEquals(70, restored.y());
		assertEquals(-10, restored.z());
		assertEquals(0.25, restored.energy());
		assertEquals(0.4, restored.social());
		assertEquals(0.75, restored.curiosity());
		assertEquals(1234, restored.respawnAt());
		assertTrue(restored.removedByAdmin());
		assertEquals(7, restored.inventory().count(s -> s.is(Items.DIAMOND), s -> s.ownedBy(owner)));
		assertEquals("Alejo", restored.inventory().slots().getFirst().ownerName());
		restored.setDirty(false);
		restored.inventory().insert(new ItemStack(Items.DIAMOND), owner, "Alejo");
		assertTrue(restored.isDirty());
	}
}
