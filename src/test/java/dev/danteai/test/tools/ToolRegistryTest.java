package dev.danteai.test.tools;

import com.google.gson.JsonObject;
import dev.danteai.llm.LlmMessage;
import dev.danteai.llm.ToolCall;
import dev.danteai.tools.Args;
import dev.danteai.tools.DanteTool;
import dev.danteai.tools.ToolContext;
import dev.danteai.tools.ToolException;
import dev.danteai.tools.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRegistryTest {
	private static final class Counting implements DanteTool {
		final AtomicInteger calls = new AtomicInteger();
		final int cooldown;

		Counting(int cooldown) {
			this.cooldown = cooldown;
		}

		@Override
		public String name() {
			return "bark";
		}

		@Override
		public String description() {
			return "ladrar";
		}

		@Override
		public JsonObject parameters() {
			JsonObject p = Args.props();
			p.add("times", Args.intProp("veces"));
			return Args.schema(p);
		}

		@Override
		public int cooldownTicks() {
			return cooldown;
		}

		@Override
		public int maxPerTurn() {
			return 2;
		}

		@Override
		public String execute(ToolContext ctx, JsonObject args) throws ToolException {
			int times = Args.integer(args, "times", 1, 1, 5);
			if (args.has("boom")) throw new IllegalStateException("bug");
			if (args.has("fail")) throw new ToolException("no tengo ganas");
			calls.incrementAndGet();
			return "guau x" + times;
		}
	}

	private static ToolCall call(String name, JsonObject args) {
		return new ToolCall("id", name, args);
	}

	@Test
	void validatesNamesArgumentsAndLimits() {
		AtomicLong time = new AtomicLong(0);
		Counting bark = new Counting(0);
		ToolRegistry registry = new ToolRegistry(time::get).register(bark);
		ToolContext ctx = new ToolContext(null, null);

		LlmMessage.ToolResult unknown = registry.execute(ctx, call("rm_rf", new JsonObject()));
		assertTrue(unknown.isError());
		assertTrue(unknown.content().contains("no existe"));

		JsonObject args = new JsonObject();
		args.addProperty("times", "3");
		LlmMessage.ToolResult ok = registry.execute(ctx, call("bark", args));
		assertFalse(ok.isError());
		assertEquals("guau x3", ok.content());

		JsonObject big = new JsonObject();
		big.addProperty("times", 999);
		assertEquals("guau x5", registry.execute(ctx, call("bark", big)).content(), "los números se acotan");

		LlmMessage.ToolResult tooMany = registry.execute(ctx, call("bark", new JsonObject()));
		assertTrue(tooMany.isError(), "límite por turno");
		assertEquals(2, bark.calls.get());

		ToolContext other = new ToolContext(null, null);
		JsonObject invalid = new JsonObject();
		invalid.addProperty("__invalid_json__", "{");
		assertTrue(registry.execute(other, call("bark", invalid)).isError());

		JsonObject fail = new JsonObject();
		fail.addProperty("fail", true);
		LlmMessage.ToolResult failed = registry.execute(other, call("bark", fail));
		assertTrue(failed.isError());
		assertEquals("no tengo ganas", failed.content());

		JsonObject boom = new JsonObject();
		boom.addProperty("boom", true);
		LlmMessage.ToolResult crashed = registry.execute(new ToolContext(null, null), call("bark", boom));
		assertTrue(crashed.isError(), "una excepción inesperada no tumba el servidor");
	}

	@Test
	void appliesGlobalCooldown() {
		AtomicLong time = new AtomicLong(1000);
		Counting bark = new Counting(100);
		ToolRegistry registry = new ToolRegistry(time::get).register(bark);
		assertFalse(registry.execute(new ToolContext(null, null), call("bark", new JsonObject())).isError());
		assertTrue(registry.execute(new ToolContext(null, null), call("bark", new JsonObject())).isError());
		time.addAndGet(100);
		assertFalse(registry.execute(new ToolContext(null, null), call("bark", new JsonObject())).isError());
	}

	@Test
	void argsValidation() {
		JsonObject a = new JsonObject();
		a.addProperty("name", "  Alejo  ");
		a.add("obj", new JsonObject());
		assertEquals("Alejo", assertDoesNotThrowString(a));
		assertThrows(ToolException.class, () -> Args.string(a, "missing", true, 10));
		assertThrows(ToolException.class, () -> Args.string(a, "obj", true, 10));
		JsonObject n = new JsonObject();
		n.addProperty("q", "todo");
		try {
			assertEquals(0, Args.integer(n, "q", 0, 0, 64));
			n.addProperty("q", "x");
			assertThrows(ToolException.class, () -> Args.integer(n, "q", 0, 0, 64));
		} catch (ToolException e) {
			throw new AssertionError(e);
		}
	}

	private static String assertDoesNotThrowString(JsonObject a) {
		try {
			return Args.string(a, "name", true, 32);
		} catch (ToolException e) {
			throw new AssertionError(e);
		}
	}
}
