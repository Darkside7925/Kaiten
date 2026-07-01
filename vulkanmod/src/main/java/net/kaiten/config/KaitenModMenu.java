package net.kaiten.config;

import net.minecraft.client.gui.screens.Screen;

/**
 * Mod Menu entrypoint for Kaiten.
 *
 * <p>This class is discovered by Mod Menu at runtime via the {@code modmenu}
 * entrypoint in fabric.mod.json. The {@code getModConfigScreenFactory} method
 * is called reflectively — no compile-time dependency on Mod Menu is needed.
 *
 * <p>If Mod Menu is NOT installed, this class is never loaded and the
 * entrypoint is silently ignored by Fabric.
 */
public final class KaitenModMenu {
    /** Called reflectively by Mod Menu. Must return a factory function. */
    @SuppressWarnings("unused")
    public static java.util.function.Function<Screen, Screen> getModConfigScreenFactory() {
        return (Screen parent) -> new KaitenSettingsScreen(parent);
    }
}
