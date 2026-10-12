package dev.danteai.test.world;

import dev.danteai.config.DanteConfig;
import dev.danteai.test.MockHttpServer;
import dev.danteai.world.ServiceMonitor;
import dev.danteai.world.ServiceMonitor.Target;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceMonitorTest {
	private final Map<String, Boolean> up = new ConcurrentHashMap<>();

	private ServiceMonitor monitor(String... urls) {
		ServiceMonitor m = new ServiceMonitor(url -> CompletableFuture.completedFuture(up.getOrDefault(url, false)));
		List<Target> targets = new ArrayList<>();
		for (String url : urls) targets.add(new Target(url, url));
		m.configure(targets, 3600);
		return m;
	}

	@Test
	void notAvailableUntilEveryServiceResponds() {
		ServiceMonitor m = monitor("llm", "tts");
		m.checkNow();
		assertFalse(m.available());
		assertEquals(List.of("llm", "tts"), m.missing());

		up.put("llm", true);
		m.checkNow();
		assertFalse(m.available());
		assertEquals(List.of("tts"), m.missing());

		up.put("tts", true);
		m.checkNow();
		assertTrue(m.available());
		assertTrue(m.missing().isEmpty());
		m.shutdown();
	}

	@Test
	void toleratesBriefOutagesButNotSustainedOnes() {
		up.put("llm", true);
		ServiceMonitor m = monitor("llm");
		m.checkNow();
		assertTrue(m.available());

		up.put("llm", false);
		m.checkNow();
		m.checkNow();
		assertTrue(m.available(), "dos fallos seguidos no deberían retirar a Dante");
		m.checkNow();
		assertFalse(m.available());

		up.put("llm", true);
		m.checkNow();
		assertTrue(m.available(), "con una sola respuesta vuelve");
		m.shutdown();
	}

	@Test
	void reportsStateChangesOnce() {
		List<String> events = new ArrayList<>();
		ServiceMonitor m = monitor("llm");
		m.setListener((ok, missing) -> events.add(ok + ":" + missing));
		m.checkNow();
		m.checkNow();
		up.put("llm", true);
		m.checkNow();
		m.checkNow();
		assertEquals(List.of("false:[llm]", "true:[]"), events);
		m.shutdown();
	}

	@Test
	void withoutTargetsItIsAvailable() {
		ServiceMonitor m = monitor();
		m.checkNow();
		assertTrue(m.available());
		m.shutdown();
	}

	@Test
	void aThrowingProbeCountsAsDown() {
		ServiceMonitor m = new ServiceMonitor(url -> {
			throw new IllegalStateException("boom");
		});
		m.configure(List.of(new Target("llm", "x")), 3600);
		m.checkNow();
		assertFalse(m.available());
		m.shutdown();
	}

	@Test
	void targetsFollowTheConfiguration() {
		DanteConfig c = new DanteConfig();
		assertEquals(List.of("LLM", "voz (TTS)", "escucha (STT)"), ServiceMonitor.targets(c).stream().map(Target::name).toList());

		c.voice.listen.enabled = false;
		assertEquals(List.of("LLM", "voz (TTS)"), ServiceMonitor.targets(c).stream().map(Target::name).toList());

		c.voice.enabled = false;
		c.llm.provider = "anthropic";
		List<Target> t = ServiceMonitor.targets(c);
		assertEquals(1, t.size());
		assertEquals(c.llm.anthropic.base_url, t.getFirst().url());

		c.llm.enabled = false;
		assertTrue(ServiceMonitor.targets(c).isEmpty());
	}

	@Test
	void httpProbeTreatsAnyResponseAsReachableButNotProxyErrors() throws Exception {
		try (MockHttpServer server = new MockHttpServer()) {
			server.always(r -> MockHttpServer.Reply.json(r.path().equals("/bad-gateway") ? 502 : 404, "{}"));
			assertTrue(ServiceMonitor.httpProbe(server.baseUrl() + "/v1").get());
			assertFalse(ServiceMonitor.httpProbe(server.baseUrl() + "/bad-gateway").get());
		}
		assertFalse(ServiceMonitor.httpProbe("http://127.0.0.1:1/v1").get());
		assertFalse(ServiceMonitor.httpProbe("no es una url").get());
	}
}
