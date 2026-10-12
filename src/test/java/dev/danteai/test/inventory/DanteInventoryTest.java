package dev.danteai.test.inventory;

import dev.danteai.inventory.DanteInventory;
import dev.danteai.inventory.ItemMatcher;
import dev.danteai.inventory.OwnedStack;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DanteInventoryTest {
	private static final UUID ALEJO = UUID.randomUUID();
	private static final UUID ANA = UUID.randomUUID();

	@BeforeAll
	static void bootstrap() {
		dev.danteai.test.McBootstrap.init();
	}

	private static int total(DanteInventory inv) {
		return inv.count(s -> true, s -> true);
	}

	@Test
	void separatesStacksByOwnerAndMergesSameOwner() {
		DanteInventory inv = new DanteInventory(5);
		AtomicInteger changes = new AtomicInteger();
		inv.setOnChange(changes::incrementAndGet);
		ItemStack a = new ItemStack(Items.DIAMOND, 10);
		assertEquals(10, inv.insert(a, ALEJO, "Alejo"));
		assertTrue(a.isEmpty(), "la pila de origen se vacía: el objeto se movió, no se copió");
		inv.insert(new ItemStack(Items.DIAMOND, 5), ALEJO, "Alejo");
		inv.insert(new ItemStack(Items.DIAMOND, 3), ANA, "Ana");
		assertEquals(2, inv.slots().size(), "mismo dueño se combina, distinto dueño no");
		assertEquals(15, inv.count(s -> s.is(Items.DIAMOND), s -> s.ownedBy(ALEJO)));
		assertEquals(3, inv.count(s -> s.is(Items.DIAMOND), s -> s.ownedBy(ANA)));
		assertTrue(changes.get() >= 3);
	}

	@Test
	void respectsCapacityAndReportsLeftovers() {
		DanteInventory inv = new DanteInventory(2);
		ItemStack big = new ItemStack(Items.COBBLESTONE, 64);
		inv.insert(big, ALEJO, "Alejo");
		ItemStack more = new ItemStack(Items.DIRT, 64);
		inv.insert(more, ALEJO, "Alejo");
		ItemStack extra = new ItemStack(Items.SAND, 10);
		assertEquals(0, inv.insert(extra, ALEJO, "Alejo"));
		assertEquals(10, extra.getCount(), "lo que no entra queda en la pila original");
		assertEquals(0, inv.roomFor(new ItemStack(Items.SAND), ALEJO));
		assertEquals(0, inv.freeSlots());
	}

	@Test
	void extractPrefersRequesterAndRestoreKeepsOwners() {
		DanteInventory inv = new DanteInventory(9);
		inv.insert(new ItemStack(Items.DIAMOND, 4), ANA, "Ana");
		inv.insert(new ItemStack(Items.DIAMOND, 6), ALEJO, "Alejo");
		inv.insert(new ItemStack(Items.BONE, 2), null, "");
		List<OwnedStack> out = inv.extract(s -> s.is(Items.DIAMOND), s -> true, 7, ALEJO);
		assertEquals(6, out.getFirst().stack().getCount());
		assertTrue(out.getFirst().ownedBy(ALEJO), "primero lo del que pide");
		assertEquals(1, out.get(1).stack().getCount());
		assertEquals(3, inv.count(s -> s.is(Items.DIAMOND), s -> true));

		inv.restore(out);
		assertEquals(6, inv.count(s -> s.is(Items.DIAMOND), s -> s.ownedBy(ALEJO)));
		assertEquals(4, inv.count(s -> s.is(Items.DIAMOND), s -> s.ownedBy(ANA)));

		List<OwnedStack> onlyAlejo = inv.extract(s -> true, s -> s.ownedBy(ALEJO), 0, ALEJO);
		assertEquals(6, onlyAlejo.stream().mapToInt(s -> s.stack().getCount()).sum());
		assertEquals(0, inv.count(s -> true, s -> s.ownedBy(ALEJO)));
		assertEquals(6, total(inv), "lo de Ana y el hueso siguen");
	}

	@Test
	void restoreNeverLosesItemsEvenWhenFull() {
		DanteInventory inv = new DanteInventory(1);
		inv.insert(new ItemStack(Items.STONE, 64), ALEJO, "Alejo");
		inv.restore(List.of(new OwnedStack(new ItemStack(Items.EMERALD, 3), ANA, "Ana")));
		assertEquals(3, inv.count(s -> s.is(Items.EMERALD), s -> s.ownedBy(ANA)));
		assertEquals(67, total(inv));
	}

	@Test
	void randomOperationsConserveItems() {
		Random r = new Random(123);
		DanteInventory inv = new DanteInventory(6);
		int outside = 0;
		int inside = 0;
		UUID[] owners = {ALEJO, ANA, null};
		var items = List.of(Items.DIAMOND, Items.BONE, Items.STICK, Items.IRON_SWORD);
		for (int i = 0; i < 2000; i++) {
			if (r.nextBoolean()) {
				var item = items.get(r.nextInt(items.size()));
				ItemStack s = new ItemStack(item, 1 + r.nextInt(Math.max(1, new ItemStack(item).getMaxStackSize())));
				int before = s.getCount();
				int inserted = inv.insert(s, owners[r.nextInt(3)], "x");
				assertEquals(before, inserted + s.getCount());
				inside += inserted;
			} else {
				var item = items.get(r.nextInt(items.size()));
				List<OwnedStack> out = inv.extract(s -> s.is(item), s -> true, r.nextInt(80), owners[r.nextInt(3)]);
				int n = out.stream().mapToInt(s -> s.stack().getCount()).sum();
				inside -= n;
				outside += n;
			}
			assertEquals(inside, total(inv), "conservación estricta en la operación " + i);
			assertTrue(inv.slots().size() <= 6);
		}
		assertTrue(outside > 0);
	}

	@Test
	void itemMatcherResolvesIdsNamesAndSpanishAliases() {
		Predicate<ItemStack> byId = ItemMatcher.parse("minecraft:diamond");
		assertTrue(byId.test(new ItemStack(Items.DIAMOND)));
		assertTrue(!byId.test(new ItemStack(Items.DIAMOND_SWORD)));
		assertTrue(ItemMatcher.parse("diamond").test(new ItemStack(Items.DIAMOND)));
		assertTrue(ItemMatcher.parse("diamantes").test(new ItemStack(Items.DIAMOND)));
		assertTrue(ItemMatcher.parse("Hueso").test(new ItemStack(Items.BONE)));
		assertTrue(ItemMatcher.parse("espada de hierro").test(new ItemStack(Items.IRON_SWORD)));
		assertTrue(ItemMatcher.parse("espada").test(new ItemStack(Items.IRON_SWORD)));
		assertTrue(ItemMatcher.parse("Iron Sword").test(new ItemStack(Items.IRON_SWORD)));
		assertNull(ItemMatcher.parse("all"));
		assertNull(ItemMatcher.parse("todo"));
	}
}
