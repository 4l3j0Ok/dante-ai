package dev.danteai.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/** Corrige valores fuera de rango para que una configuración mal escrita nunca rompa el servidor. */
public final class ConfigValidator {
	private ConfigValidator() {
	}

	public static List<String> validate(DanteConfig c) {
		List<String> warnings = new ArrayList<>();
		if (c.dante == null) c.dante = new DanteConfig.Dante();
		if (c.behavior == null) c.behavior = new DanteConfig.Behavior();
		if (c.llm == null) c.llm = new DanteConfig.Llm();
		if (c.llm.openai == null) c.llm.openai = new DanteConfig.OpenAi();
		if (c.llm.openai.extra_headers == null) c.llm.openai.extra_headers = new LinkedHashMap<>();
		if (c.llm.anthropic == null) c.llm.anthropic = new DanteConfig.Anthropic();
		if (c.chat == null) c.chat = new DanteConfig.Chat();
		if (c.spontaneous == null) c.spontaneous = new DanteConfig.Spontaneous();
		if (c.inventory == null) c.inventory = new DanteConfig.Inventory();
		if (c.chests == null) c.chests = new DanteConfig.Chests();
		if (c.memory == null) c.memory = new DanteConfig.Memory();
		if (c.voice == null) c.voice = new DanteConfig.Voice();
		if (c.voice.openai == null) c.voice.openai = new DanteConfig.OpenAiTts();
		if (c.voice.listen == null) c.voice.listen = new DanteConfig.Listen();
		if (c.logging == null) c.logging = new DanteConfig.Logging();

		if (c.dante.name == null || c.dante.name.isBlank()) {
			c.dante.name = "Dante";
		}
		c.dante.name = c.dante.name.strip();
		if (c.dante.name.length() > 24) c.dante.name = c.dante.name.substring(0, 24);
		c.dante.respawn_delay_seconds = clamp(c.dante.respawn_delay_seconds, 0, 3600);
		c.dante.admin_permission_level = clamp(c.dante.admin_permission_level, 0, 4);
		c.dante.service_check_seconds = clamp(c.dante.service_check_seconds, 3, 600);

		DanteConfig.Behavior b = c.behavior;
		b.follow_probability = clamp(b.follow_probability, 0, 1);
		b.idle_probability = clamp(b.idle_probability, 0, 1);
		b.explore_probability = clamp(b.explore_probability, 0, 1);
		b.socialize_probability = clamp(b.socialize_probability, 0, 1);
		b.ignore_probability = clamp(b.ignore_probability, 0, 1);
		b.wander_radius = clamp(b.wander_radius, 2, 64);
		b.explore_radius = clamp(b.explore_radius, 2, 32);
		b.social_radius = clamp(b.social_radius, 4, 64);
		b.follow_min_seconds = clamp(b.follow_min_seconds, 5, 3600);
		b.follow_max_seconds = clamp(b.follow_max_seconds, b.follow_min_seconds, 7200);
		b.requested_follow_seconds = clamp(b.requested_follow_seconds, 10, 7200);
		b.follow_stop_distance = clamp(b.follow_stop_distance, 1.5, 10);
		b.rest_min_seconds = clamp(b.rest_min_seconds, 3, 3600);
		b.rest_max_seconds = clamp(b.rest_max_seconds, b.rest_min_seconds, 7200);
		b.teleport_distance = clamp(b.teleport_distance, 16, 10000);
		b.teleport_cooldown_seconds = clamp(b.teleport_cooldown_seconds, 0, 86400);
		b.lonely_minutes_before_seeking = clamp(b.lonely_minutes_before_seeking, 1, 1440);
		b.visit_after_minutes = clamp(b.visit_after_minutes, 1, 1440);
		b.visit_reply_seconds = clamp(b.visit_reply_seconds, 5, 600);
		b.visit_stay_minutes = clamp(b.visit_stay_minutes, 1, 1440);

		DanteConfig.Llm l = c.llm;
		l.provider = lower(l.provider, "openai");
		if (!l.provider.equals("openai") && !l.provider.equals("anthropic")) {
			warnings.add("llm.provider desconocido '" + l.provider + "', se usa 'openai'");
			l.provider = "openai";
		}
		if (l.temperature >= 0) l.temperature = clamp(l.temperature, 0, 2);
		l.max_tokens = clamp(l.max_tokens, 32, 8192);
		l.timeout_seconds = clamp(l.timeout_seconds, 3, 600);
		l.max_context_chars = clamp(l.max_context_chars, 2000, 400000);
		l.max_concurrent_requests = clamp(l.max_concurrent_requests, 1, 16);
		l.max_queue = clamp(l.max_queue, 0, 256);
		l.requests_per_minute = clamp(l.requests_per_minute, 1, 10000);
		l.max_retries = clamp(l.max_retries, 0, 5);
		l.max_tool_rounds = clamp(l.max_tool_rounds, 1, 8);
		l.openai.max_tokens_field = lower(l.openai.max_tokens_field, "max_tokens");
		if (!l.openai.max_tokens_field.equals("max_tokens") && !l.openai.max_tokens_field.equals("max_completion_tokens")) {
			l.openai.max_tokens_field = "max_tokens";
		}
		l.openai.tools_mode = lower(l.openai.tools_mode, "native");
		if (!l.openai.tools_mode.equals("native") && !l.openai.tools_mode.equals("json")) {
			l.openai.tools_mode = "native";
		}
		if (l.openai.api_key == null) l.openai.api_key = "";
		if (l.anthropic.api_key == null) l.anthropic.api_key = "";
		if (l.system_prompt_file == null || l.system_prompt_file.isBlank()) l.system_prompt_file = "system_prompt.md";

		DanteConfig.Chat ch = c.chat;
		if (ch.trigger_names == null || ch.trigger_names.isEmpty()) {
			ch.trigger_names = new ArrayList<>(List.of(c.dante.name));
		}
		ch.reply_audience = lower(ch.reply_audience, "all");
		if (!ch.reply_audience.equals("all") && !ch.reply_audience.equals("nearby")) ch.reply_audience = "all";
		ch.followup_seconds = clamp(ch.followup_seconds, 0, 600);
		ch.proximity_radius = clamp(ch.proximity_radius, 2, 64);
		ch.max_message_length = clamp(ch.max_message_length, 40, 1000);
		ch.max_input_length = clamp(ch.max_input_length, 20, 2000);
		ch.player_cooldown_seconds = clamp(ch.player_cooldown_seconds, 0, 600);
		ch.player_requests_per_hour = clamp(ch.player_requests_per_hour, 1, 100000);

		c.spontaneous.min_interval_seconds = clamp(c.spontaneous.min_interval_seconds, 20, 86400);
		c.spontaneous.chance = clamp(c.spontaneous.chance, 0, 1);

		c.inventory.slots = clamp(c.inventory.slots, 1, 54);
		c.inventory.give_reach = clamp(c.inventory.give_reach, 1.5, 8);
		c.inventory.delivery_max_distance = clamp(c.inventory.delivery_max_distance, 4, 256);
		if (c.inventory.autonomous_pickup == null) c.inventory.autonomous_pickup = new ArrayList<>();

		c.chests.search_radius = clamp(c.chests.search_radius, 2, 32);
		if (c.chests.allowed_containers == null) c.chests.allowed_containers = new ArrayList<>();
		if (c.chests.container_keyword == null) c.chests.container_keyword = "Dante";

		c.memory.context_messages = clamp(c.memory.context_messages, 0, 100);
		c.memory.summarize_after_messages = clamp(c.memory.summarize_after_messages, 10, 1000);
		c.memory.context_facts = clamp(c.memory.context_facts, 0, 100);
		c.memory.retention_days = clamp(c.memory.retention_days, 0, 36500);

		DanteConfig.Voice v = c.voice;
		v.distance = (float) clamp(v.distance, 4, 128);
		v.pitch = clamp(v.pitch, 0.5, 2.0);
		v.gain = clamp(v.gain, 0.1, 4.0);
		v.max_characters = clamp(v.max_characters, 20, 4000);
		v.timeout_seconds = clamp(v.timeout_seconds, 3, 300);
		v.max_concurrent = clamp(v.max_concurrent, 1, 8);
		v.openai.speed = clamp(v.openai.speed, 0.25, 4.0);
		if (v.openai.api_key == null) v.openai.api_key = "";
		v.listen.distance = clamp(v.listen.distance, 2, 64);
		v.listen.silence_ms = clamp(v.listen.silence_ms, 300, 5000);
		v.listen.max_seconds = clamp(v.listen.max_seconds, 2, 60);
		v.listen.min_ms = clamp(v.listen.min_ms, 100, 5000);
		if (v.listen.api_key == null) v.listen.api_key = "";
		if (v.listen.language == null) v.listen.language = "";
		return warnings;
	}

	private static String lower(String s, String def) {
		return s == null || s.isBlank() ? def : s.strip().toLowerCase(Locale.ROOT);
	}

	static int clamp(int v, int min, int max) {
		return Math.max(min, Math.min(max, v));
	}

	static double clamp(double v, double min, double max) {
		if (Double.isNaN(v)) return min;
		return Math.max(min, Math.min(max, v));
	}
}
