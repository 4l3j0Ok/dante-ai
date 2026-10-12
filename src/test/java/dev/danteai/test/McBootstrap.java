package dev.danteai.test;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

/**
 * Inicializa Minecraft 1.21.1 para usar {@code ItemStack} en tests unitarios.
 */
public final class McBootstrap {
	private static boolean done;

	private McBootstrap() {
	}

	public static synchronized void init() {
		if (done) return;
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		done = true;
	}
}
