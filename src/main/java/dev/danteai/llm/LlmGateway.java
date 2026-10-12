package dev.danteai.llm;

import dev.danteai.config.DanteConfig;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Punto único de acceso al modelo: elige el proveedor según la configuración y aplica los límites globales. */
public final class LlmGateway {
	private final DanteConfig.Llm config;
	private final LlmProvider provider;
	private final RequestScheduler scheduler;

	public LlmGateway(DanteConfig.Llm config) {
		this(config, createProvider(config));
	}

	public LlmGateway(DanteConfig.Llm config, LlmProvider provider) {
		this.config = config;
		this.provider = provider;
		this.scheduler = new RequestScheduler(config.max_concurrent_requests, config.max_queue, config.requests_per_minute);
	}

	public static LlmProvider createProvider(DanteConfig.Llm cfg) {
		Duration timeout = Duration.ofSeconds(cfg.timeout_seconds);
		if (cfg.provider.equals("anthropic")) {
			DanteConfig.Anthropic a = cfg.anthropic;
			return new AnthropicProvider(new AnthropicProvider.Settings(a.base_url, a.api_key, a.model, a.api_version, timeout, cfg.max_retries));
		}
		DanteConfig.OpenAi o = cfg.openai;
		return new OpenAiCompatibleProvider(new OpenAiCompatibleProvider.Settings(o.base_url, o.api_key, o.model, o.max_tokens_field,
			o.tools_mode.equals("json"), o.extra_headers, timeout, cfg.max_retries, o.reasoning_effort));
	}

	public boolean enabled() {
		return config.enabled && (!config.provider.equals("anthropic") || !config.anthropic.api_key.isBlank());
	}

	public String describe() {
		return provider.name() + " / " + provider.model();
	}

	public Double temperature() {
		return config.temperature < 0 ? null : config.temperature;
	}

	public int maxTokens() {
		return config.max_tokens;
	}

	public int maxToolRounds() {
		return config.max_tool_rounds;
	}

	public CompletableFuture<LlmResponse> complete(LlmRequest request) {
		return scheduler.submit(() -> provider.complete(request));
	}

	/** Pedido simple sin herramientas (resúmenes de memoria, pruebas). */
	public CompletableFuture<String> simple(String system, String user, int maxTokens) {
		return complete(new LlmRequest(system, List.of(new LlmMessage.User(user)), List.of(), temperature(), maxTokens))
			.thenApply(LlmResponse::text);
	}

	public int active() {
		return scheduler.active();
	}

	public int queued() {
		return scheduler.queued();
	}

	public void shutdown() {
		scheduler.cancelAll();
	}
}
