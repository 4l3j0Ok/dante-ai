package dev.danteai;

import dev.danteai.command.DanteCommands;
import dev.danteai.config.ConfigIO;
import dev.danteai.config.ConfigValidator;
import dev.danteai.config.DanteConfig;
import dev.danteai.entity.ModEntities;
import dev.danteai.memory.MemoryService;
import dev.danteai.util.DanteLog;
import dev.danteai.world.DanteManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Punto de entrada del mod (servidor y cliente). */
public final class DanteAi implements ModInitializer {
	public static final String MOD_ID = "danteai";

	private static volatile DanteConfig config = new DanteConfig();
	private static volatile DanteManager manager;

	public static ResourceLocation id(String path) {
		return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
	}

	public static DanteConfig config() {
		return config;
	}

	/** Administrador del servidor activo, o {@code null} si no hay servidor (o en el cliente). */
	public static DanteManager manager() {
		return manager;
	}

	public static Path configDir() {
		return FabricLoader.getInstance().getConfigDir().resolve(MOD_ID);
	}

	@Override
	public void onInitialize() {
		ModEntities.register();
		loadConfig();

		ServerLifecycleEvents.SERVER_STARTED.register(DanteAi::onServerStarted);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			DanteManager m = manager;
			manager = null;
			if (m != null) m.shutdown();
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			DanteManager m = manager;
			if (m != null) m.tick();
		});
		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			DanteManager m = manager;
			if (m != null) m.onEntityLoad(entity, level);
		});
		ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
			DanteManager m = manager;
			if (m != null) m.onEntityUnload(entity, level);
		});
		// CHAT_MESSAGE se dispara después de que el mensaje se difundió normalmente: Dante responde a continuación.
		ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
			DanteManager m = manager;
			if (m != null) m.conversation().onChat(sender, message.signedContent());
		});
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> DanteCommands.register(dispatcher));
		DanteLog.info("Dante AI inicializado");
	}

	private static void onServerStarted(MinecraftServer server) {
		Path dbFile = server.getWorldPath(LevelResource.ROOT).resolve(MOD_ID).resolve("memory.db");
		var store = MemoryService.openStore(dbFile, config.memory.persist);
		manager = new DanteManager(server, config, loadPersonality(config), store);
		DanteLog.info("Dante listo. LLM: {} | memoria: {} | voz: {}", manager.llm().enabled() ? manager.llm().describe() : "desactivado",
			store.persistent() ? "SQLite" : "temporal", manager.voice().status());
	}

	/** Carga (o crea) la configuración. Ante errores conserva la configuración anterior. */
	public static synchronized String loadConfig() {
		Path file = configDir().resolve("config.jsonc");
		try {
			DanteConfig loaded = ConfigIO.loadOrCreate(file);
			for (String warning : ConfigValidator.validate(loaded)) DanteLog.warn("Configuración: {}", warning);
			config = loaded;
			DanteLog.configure(loaded.logging.debug, loaded.logging.log_prompts, loaded.logging.log_tool_calls);
			return null;
		} catch (IOException | RuntimeException e) {
			DanteLog.error("No se pudo leer " + file + "; se mantiene la configuración anterior", e);
			return e.getMessage();
		}
	}

	/** Recarga configuración y personalidad, y las aplica al servidor en marcha. */
	public static String reload() {
		String error = loadConfig();
		DanteManager m = manager;
		if (m != null) m.reload(config, loadPersonality(config));
		return error;
	}

	static String loadPersonality(DanteConfig cfg) {
		Path file = configDir().resolve(cfg.llm.system_prompt_file);
		try {
			if (!Files.exists(file)) {
				Files.createDirectories(file.getParent());
				try (InputStream in = DanteAi.class.getResourceAsStream("/danteai/system_prompt.md")) {
					if (in != null) Files.copy(in, file);
				}
			}
			String text = Files.readString(file, StandardCharsets.UTF_8);
			return text.replace("Dante", cfg.dante.name);
		} catch (IOException e) {
			DanteLog.error("No se pudo leer la personalidad " + file, e);
			return "Sos " + cfg.dante.name + ", un perro labrador negro, curioso y juguetón que vive en Minecraft.";
		}
	}
}
