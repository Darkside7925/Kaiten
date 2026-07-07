package net.kaiten;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.kaiten.config.KaitenSettingsScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
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
                // --- Live upscaling-quality tuning (watch the screen while adjusting) ---
                .then(literal("tune")
                        .executes(ctx -> { showTune(ctx.getSource()); return 1; })
                        .then(literal("jitter")
                                .then(literal("on").executes(ctx -> { DlssFrameState.applyJitter = true;
                                    feedback(ctx.getSource(), "jitter ON"); return 1; }))
                                .then(literal("off").executes(ctx -> { DlssFrameState.applyJitter = false;
                                    feedback(ctx.getSource(), "jitter OFF"); return 1; })))
                        .then(literal("mv")
                                .then(literal("on").executes(ctx -> { DlssSuperResolution.useMotionVectors = true;
                                    feedback(ctx.getSource(), "motion vectors ON"); return 1; }))
                                .then(literal("off").executes(ctx -> { DlssSuperResolution.useMotionVectors = false;
                                    feedback(ctx.getSource(), "motion vectors OFF"); return 1; }))
                                .then(literal("flip").executes(ctx -> {
                                    DlssSuperResolution.mvSign = -DlssSuperResolution.mvSign;
                                    feedback(ctx.getSource(), "MV sign = " + DlssSuperResolution.mvSign); return 1; })))
                        .then(literal("mvscale")
                                .then(argument("v", com.mojang.brigadier.arguments.FloatArgumentType.floatArg())
                                        .executes(ctx -> {
                                            DlssSuperResolution.mvScale = ctx.getArgument("v", Float.class);
                                            feedback(ctx.getSource(), "mvScale = " + DlssSuperResolution.mvScale); return 1; }))))
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

        // /iris — Vulkan shaderpack port (milestone 1: load + SPIR-V compile)
        d.register(literal("iris")
                .executes(ctx -> { irisHelp(ctx.getSource()); return 1; })
                .then(literal("list").executes(ctx -> {
                    var dir = net.kaiten.iris.IrisShaderLoader.defaultShaderpacksDir();
                    var packs = net.kaiten.iris.IrisShaderLoader.listPacks(dir);
                    irisFeedback(ctx.getSource(), "shaderpacks dir: " + dir);
                    if (packs.isEmpty()) irisFeedback(ctx.getSource(), "(no packs found — drop a .zip or folder there)");
                    else packs.forEach(p -> irisFeedback(ctx.getSource(), " - " + p));
                    return 1; }))
                .then(literal("compile")
                        .then(argument("pack", StringArgumentType.greedyString()).executes(ctx -> {
                            String pack = StringArgumentType.getString(ctx, "pack");
                            var dir = net.kaiten.iris.IrisShaderLoader.defaultShaderpacksDir();
                            irisFeedback(ctx.getSource(), "Compiling '" + pack + "' — see log for details…");
                            // Run off the client thread's critical path but on a worker to avoid stalls.
                            Minecraft.getInstance().execute(() -> {
                                var rep = net.kaiten.iris.IrisShaderLoader.loadAndCompile(dir, pack);
                                irisFeedback(ctx.getSource(), String.format(
                                        "'%s': %d programs, vtx %d, frag %d, %d need deeper transform",
                                        pack, rep.programs(), rep.vertexOk(), rep.fragmentOk(),
                                        rep.failures().size()));
                            });
                            return 1; }))));
    }

    private static void irisHelp(FabricClientCommandSource src) {
        irisFeedback(src, "=== Iris-on-Vulkan (milestone 1) ===");
        irisFeedback(src, "/iris list           — list shaderpacks");
        irisFeedback(src, "/iris compile <pack> — load a pack & attempt SPIR-V compile");
    }

    private static void irisFeedback(FabricClientCommandSource src, String msg) {
        src.sendFeedback(Component.literal("[Iris] " + msg));
    }

    private static void showStatus(FabricClientCommandSource src) {
        feedback(src, "=== DLSS Status ===");
        feedback(src, "SR (DLAA): " + (DlssSuperResolution.enabled ? "ON" : "OFF")
                + " | DLSS supported: " + NativeBridge.dlssSupported);
        showFgStatus(src);
        feedback(src, "Reflex: " + NativeBridge.reflexSupported
                + " | Debug overlay: " + (DlssDebugOverlay.enabled ? "ON" : "OFF"));
    }

    private static void showTune(FabricClientCommandSource src) {
        feedback(src, "=== DLSS upscaling tuning ===");
        feedback(src, "jitter=" + (DlssFrameState.applyJitter ? "ON" : "OFF")
                + " | mv=" + (DlssSuperResolution.useMotionVectors ? "ON" : "OFF")
                + " | mvScale=" + DlssSuperResolution.mvScale
                + " | mvSign=" + DlssSuperResolution.mvSign);
        feedback(src, "/dlss tune jitter on|off");
        feedback(src, "/dlss tune mv on|off|flip");
        feedback(src, "/dlss tune mvscale <number>  (try 1, 0.5, 2)");
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
