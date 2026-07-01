package net.vulkanmod;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.renderer.v1.Renderer;
import net.fabricmc.loader.api.FabricLoader;
import net.vulkanmod.config.Config;
import net.vulkanmod.config.Platform;
import net.vulkanmod.config.UpdateChecker;
import net.kaiten.NativeBridge;
import net.vulkanmod.render.chunk.build.frapi.VulkanModRenderer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;

public class Initializer implements ClientModInitializer {
	public static final Logger LOGGER = LogManager.getLogger("VulkanMod");

	private static String VERSION;
	public static Config CONFIG;

	@Override
	public void onInitializeClient() {
		VERSION = FabricLoader.getInstance()
				.getModContainer("vulkanmod")
				.get()
				.getMetadata()
				.getVersion().getFriendlyString();

		LOGGER.info("== VulkanMod ==");

		// DLSS native bridge bootstrap (Phase 0: prove JNI round-trip before any Streamline code).
		// Failure here is non-fatal â€” the game continues as plain VulkanMod.
		if (NativeBridge.load()) {
			NativeBridge.LOGGER.info(NativeBridge.hello("VulkanMod " + VERSION));
			NativeBridge.LOGGER.info("Native ABI version: {} (expected {})",
					NativeBridge.abiVersion(), NativeBridge.EXPECTED_ABI_VERSION);
			// Phase 1: initialize Streamline early, before the Vulkan device is created.
			NativeBridge.initStreamline();
		}

		// Phase 6: register DLSS chat commands (/dlss on|off, /dlss fg on|off|2x|3x|4x, /dlss status).
		net.kaiten.DlssCommands.register();

		// Phase 2: optional headless validation of the temporal-state math.
		if (Boolean.getBoolean("mcdlss.selftest")) {
			net.kaiten.DlssSelfTest.run();
		}

		var configPath = FabricLoader.getInstance()
				.getConfigDir()
				.resolve("vulkanmod_settings.json");

		CONFIG = loadConfig(configPath);

		// Kaiten config: per-GPU profiles, auto-loaded from config/kaiten/
		net.kaiten.config.KaitenConfig.INSTANCE.init(FabricLoader.getInstance().getConfigDir());

		Platform.init();

		Renderer.register(VulkanModRenderer.INSTANCE);

		UpdateChecker.checkForUpdates();
	}

	private static Config loadConfig(Path path) {
        return Config.load(path);
	}

	public static String getVersion() {
		return VERSION;
	}
}
