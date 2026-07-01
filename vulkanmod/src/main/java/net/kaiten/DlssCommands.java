package net.kaiten;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.kaiten.config.KaitenSettingsScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/**
 * In-game chat commands for DLSS control. Registered during client init.
 *
 * <pre>
 *   /dlss on|off       â€” Toggle DLSS Super Resolution (DLAA)
 *   /dlss fg on|off    â€” Toggle DLSS Frame Generation
 *   /dlss fg 2x|3x|4x  â€” Set FG multiplier
 *   /dlss debug        â€” Toggle debug overlay
 *   /dlss status       â€” Print current DLSS status
 * </pre>
 */
public final class DlssCommands {
    private DlssCommands() {}

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, dedicated) -> {
            registerCommands(dispatcher);
        });
    }

    private static void registerCommands(CommandDispatcher<FabricClientCommandSource> d) {
        // /dlss — legacy quick-toggle commands
        d.register(literal("dlss")
                .executes(ctx -> { showStatus(ctx.getSource()); return 1; })
                .then(literal("on").executes(ctx -> {
                    DlssSuperResolution.enabled = true;
                    feedback(ctx.getSource(), "DLSS Super Resolution: ON (DLAA)"); return 1; }))
                .then(literal("off").executes(ctx -> {
                    DlssSuperResolution.enabled = false;
                    feedback(ctx.getSource(), "DLSS Super Resolution: OFF"); return 1; }))
                .then(literal("debug").executes(ctx -> {
                    DlssDebugOverlay.enabled = !DlssDebugOverlay.enabled;
                    feedback(ctx.getSource(), "Debug overlay: " + (DlssDebugOverlay.enabled ? "ON" : "OFF")); return 1; }))
                .then(literal("fg")
                        .executes(ctx -> { showFgStatus(ctx.getSource()); return 1; })
                        .then(literal("on").executes(ctx -> {
                            DlssFrameGeneration.enabled = true;
                            feedback(ctx.getSource(), "DLSS FG: ON (" + (NativeBridge.frameGenCurrentMultiplier + 1) + "x)"); return 1; }))
                        .then(literal("off").executes(ctx -> {
                            DlssFrameGeneration.disable(); DlssFrameGeneration.enabled = false;
                            feedback(ctx.getSource(), "DLSS FG: OFF"); return 1; }))
                        .then(literal("2x").executes(ctx -> {
                            DlssFrameGeneration.enabled = true; DlssFrameGeneration.setMultiplier(1);
                            feedback(ctx.getSource(), "FG: 2x"); return 1; }))
                        .then(literal("3x").executes(ctx -> {
                            DlssFrameGeneration.enabled = true; DlssFrameGeneration.setMultiplier(2);
                            feedback(ctx.getSource(), "FG: 3x"); return 1; }))
                        .then(literal("4x").executes(ctx -> {
                            DlssFrameGeneration.enabled = true; DlssFrameGeneration.setMultiplier(3);
                            feedback(ctx.getSource(), "FG: 4x"); return 1; }))));

        // /kaiten — new unified command
        d.register(literal("kaiten")
                .executes(ctx -> { showStatus(ctx.getSource()); return 1; })
                .then(literal("settings").executes(ctx -> {
                    Minecraft.getInstance().execute(() ->
                            Minecraft.getInstance().setScreen(
                                    new KaitenSettingsScreen(Minecraft.getInstance().screen)));
                    return 1; }))
                .then(literal("report").executes(ctx -> {
                    String r = KaitenSettingsScreen.buildReport();
                    Minecraft.getInstance().keyboardHandler.setClipboard(r);
                    feedback(ctx.getSource(), "Diagnostic report copied to clipboard!");
                    return 1; })));
    }

    private static void showStatus(FabricClientCommandSource src) {
        feedback(src, "=== DLSS Status ===");
        feedback(src, "SR (DLAA): " + (DlssSuperResolution.enabled ? "ON" : "OFF")
                + " | DLSS supported: " + NativeBridge.dlssSupported);
        showFgStatus(src);
        feedback(src, "Reflex: " + NativeBridge.reflexSupported
                + " | Debug overlay: " + (DlssDebugOverlay.enabled ? "ON" : "OFF"));
    }

    private static void showFgStatus(FabricClientCommandSource src) {
        feedback(src, "FG: " + (DlssFrameGeneration.enabled ? "ON" : "OFF")
                + " | Active: " + NativeBridge.frameGenActive
                + " | Multiplier: " + (NativeBridge.frameGenCurrentMultiplier + 1) + "x"
                + " | Max: " + (NativeBridge.frameGenMaxMultiplier + 1) + "x"
                + " | Supported: " + NativeBridge.frameGenSupported);
    }

    private static void feedback(FabricClientCommandSource src, String msg) {
        src.sendFeedback(Component.literal("[DLSS] " + msg));
    }
}
