package net.kaiten.config;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.kaiten.NativeBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Kaiten settings screen with tabbed navigation.
 * Tabs: DLSS | GPU | Profiles | General.
 */
@Environment(EnvType.CLIENT)
public final class KaitenSettingsScreen extends Screen {
    private static final int TAB_H = 22, PAD = 8;
    private final Screen parent;
    private int tab = 0;
    private static final String[] TABS = {"DLSS", "GPU", "Profiles", "General"};
    private final List<Label> labels = new ArrayList<>();

    public KaitenSettingsScreen(Screen parent) {
        super(Component.literal("Kaiten Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        labels.clear();
        int tabW = (width - PAD * 2) / 4;
        for (int i = 0; i < 4; i++) {
            final int ti = i;
            addRenderableWidget(Button.builder(Component.literal(TABS[i]), b -> switchTab(ti))
                    .pos(PAD + i * tabW, PAD).size(tabW - 2, TAB_H).build());
        }
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                .pos(width / 2 - 100, height - 28).size(200, 20).build());
        rebuildTab();
    }

    private void switchTab(int t) { tab = t; rebuildTab(); }

    private void rebuildTab() {
        clearWidgets();
        labels.clear();
        int tabW = (width - PAD * 2) / 4;
        for (int i = 0; i < 4; i++) {
            final int ti = i;
            addRenderableWidget(Button.builder(Component.literal(TABS[i]), b -> switchTab(ti))
                    .pos(PAD + i * tabW, PAD).size(tabW - 2, TAB_H).build());
        }
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                .pos(width / 2 - 100, height - 28).size(200, 20).build());

        switch (tab) {
            case 0 -> buildDlss(); case 1 -> buildGpu();
            case 2 -> buildProfiles(); case 3 -> buildGeneral();
        }
    }

    private void buildDlss() {
        int y = 38, x = PAD + 10;
        var p = KaitenConfig.INSTANCE.getActiveProfile();
        if (p == null) { lbl("Kaiten not yet initialized. Load a world first.", y); return; }

        lbl("\u00a7nSuper Resolution", y); y += 18;
        lbl("  DLSS SR (DLAA): " + onOff(p.dlssEnabled), y); y += 14;
        btn(x, y, 140, "Toggle SR", b -> { p.dlssEnabled = !p.dlssEnabled; applyLive(); rebuildTab(); }); y += 24;

        String[] presets = {"Ultra Perf", "Perf", "Balanced", "Quality", "Ultra Quality", "DLAA"};
        int[] pmodes = {NativeBridge.DLSS_ULTRA_PERF, NativeBridge.DLSS_PERF, NativeBridge.DLSS_BALANCED,
                NativeBridge.DLSS_QUALITY, NativeBridge.DLSS_ULTRA_QUALITY, NativeBridge.DLSS_DLAA};
        int ci = 5; for (int i = 0; i < pmodes.length; i++) if (pmodes[i] == p.dlssMode) { ci = i; break; }
        cycle(x, y, 130, "Preset", presets, ci, idx -> { p.dlssMode = pmodes[idx]; applyLive(); }); y += 26;

        y += 6;
        lbl("\u00a7nFrame Generation", y); y += 18;
        lbl("  FG: " + onOff(p.fgEnabled) + " | Multiplier: " + (p.fgEnabled ? (p.fgMultiplier+1)+"x" : "Off"), y); y += 14;
        btn(x, y, 140, "Toggle FG", b -> { p.fgEnabled = !p.fgEnabled; applyFg(); rebuildTab(); }); y += 24;

        int max = NativeBridge.frameGenMaxMultiplier;
        String[] mopts = new String[Math.min(max, 3)];
        for (int i = 0; i < mopts.length; i++) mopts[i] = (i+2) + "x";
        int mi = Math.max(0, Math.min(p.fgMultiplier-1, mopts.length-1));
        if (mopts.length > 0) {
            cycle(x, y, 100, "Mult", mopts, mi, idx -> { p.fgMultiplier = idx+1; applyFg(); }); y += 26;
        }
        lbl("  Max: " + (max+1) + "x (Blackwell MFG)", y); y += 14;

        y += 6;
        lbl("\u00a7nReflex", y); y += 18;
        String[] refmodes = {"Off", "On", "On+Boost"};
        cycle(x, y, 110, "Mode", refmodes, Math.min(p.reflexMode, 2),
                idx -> { p.reflexMode = idx; NativeBridge.setupReflex(idx); }); y += 26;

        y += 6;
        lbl("\u00a7nDebug", y); y += 18;
        lbl("  Overlay: " + onOff(p.debugOverlay), y); y += 14;
        btn(x, y, 140, "Toggle Overlay", b -> {
            p.debugOverlay = !p.debugOverlay; net.kaiten.DlssDebugOverlay.enabled = p.debugOverlay; rebuildTab(); }); y += 24;

        lbl("  Diagnostic Report:", y); y += 14;
        btn(x, y, 160, "Copy to Clipboard", b -> copyReport());
    }

    private void buildGpu() {
        int y = 38;
        String gpu = "Unknown";
        try { var d = net.vulkanmod.vulkan.device.DeviceManager.device; if (d != null) gpu = d.deviceName; } catch (Throwable ignored) {}

        lbl("\u00a7nHardware", y); y += 18;
        lbl("  GPU: " + gpu, y); y += 14;
        lbl("  DLSS SR: " + check(NativeBridge.dlssSupported) + " (Turing+)", y); y += 14;
        lbl("  Frame Gen: " + check(NativeBridge.frameGenSupported) + " (Ada+)", y); y += 14;
        lbl("  MFG 3x/4x: " + check(NativeBridge.frameGenMaxMultiplier >= 2)
                + " (Blackwell; max " + (NativeBridge.frameGenMaxMultiplier+1) + "x)", y); y += 14;
        lbl("  Reflex: " + check(NativeBridge.reflexSupported), y); y += 14;

        if (!NativeBridge.dlssSupported && !NativeBridge.frameGenSupported) {
            y += 8; lbl("  DLSS not available on this GPU.", y); y += 14;
            lbl("  Kaiten runs as Vulkan renderer only.", y);
        }
    }

    private void buildProfiles() {
        int y = 38, x = PAD + 10;
        var p = KaitenConfig.INSTANCE.getActiveProfile();
        if (p == null) { lbl("No profile active.", y); return; }

        lbl("\u00a7nActive: " + p.name, y); y += 18;
        lbl("  SR:" + onOff(p.dlssEnabled) + " FG:" + (p.fgEnabled?p.multiplierLabel():"Off")
                + " Reflex:" + (p.reflexMode==0?"Off":p.reflexMode==1?"On":"Boost"), y); y += 16;

        y += 6;
        lbl("\u00a7nProfiles for this GPU", y); y += 18;
        var profiles = KaitenConfig.INSTANCE.getProfiles(
                KaitenConfig.INSTANCE.getActiveGpuKey() != null ? KaitenConfig.INSTANCE.getActiveGpuKey() : "");
        for (var pf : profiles) {
            boolean act = pf == p;
            btn(x, y, 200, (act ? "> " : "  ") + pf.name, b -> KaitenConfig.INSTANCE.switchToProfile(pf));
            y += 22;
        }
        y += 6;
        btn(x, y, 120, "New Profile", b -> KaitenConfig.INSTANCE.createProfile("Profile " + (profiles.size()+1)));
    }

    private void buildGeneral() {
        int y = 38, x = PAD + 10;
        lbl("\u00a7nGeneral", y); y += 18;
        lbl("  Update check: " + onOff(KaitenConfig.INSTANCE.updateCheck), y); y += 14;
        btn(x, y, 140, "Toggle Update Check", b -> {
            KaitenConfig.INSTANCE.updateCheck = !KaitenConfig.INSTANCE.updateCheck;
            KaitenConfig.INSTANCE.saveGlobal(); rebuildTab(); }); y += 24;

        y += 6;
        lbl("\u00a7nDiagnostics", y); y += 18;
        btn(x, y, 160, "Copy Diagnostic Report", b -> copyReport());
    }

    private void lbl(String text, int y) { labels.add(new Label(text, y)); }
    private void btn(int x, int y, int w, String text, java.util.function.Consumer<Button> onClick) {
        addRenderableWidget(Button.builder(Component.literal(text), b -> onClick.accept((Button)b)).pos(x,y).size(w,20).build());
    }
    private void cycle(int x, int y, int w, String prefix, String[] opts, int cur, java.util.function.IntConsumer onChange) {
        // Simple button that cycles through options on click
        final int[] idx = {cur};
        Runnable advance = () -> {
            idx[0] = (idx[0] + 1) % opts.length;
            onChange.accept(idx[0]);
            rebuildTab();
        };
        String label = prefix + ": " + opts[cur];
        addRenderableWidget(Button.builder(Component.literal(label), b -> advance.run())
                .pos(x, y).size(w, 20).build());
    }
    private void applyLive() { KaitenConfig.INSTANCE.saveProfiles(); }
    private void applyFg() {
        var p = KaitenConfig.INSTANCE.getActiveProfile(); if (p == null) return;
        if (p.fgEnabled) { net.kaiten.DlssFrameGeneration.enabled = true; net.kaiten.DlssFrameGeneration.setMultiplier(p.fgMultiplier); }
        else { net.kaiten.DlssFrameGeneration.enabled = false; net.kaiten.DlssFrameGeneration.disable(); }
        KaitenConfig.INSTANCE.saveProfiles();
    }
    private void copyReport() {
        String r = buildReport(); minecraft.keyboardHandler.setClipboard(r);
        if (minecraft.player != null) minecraft.player.displayClientMessage(Component.literal("Report copied!"), false);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        // Fill with a dark background instead of calling renderBackground()
        // (which fails with "Can only blur once per frame" when opened from VOptionScreen).
        g.fill(0, 0, width, height, 0xC0101010);
        for (int i = 0; i < 4; i++) {
            int tw = (width - PAD*2)/4, tx = PAD + i*tw;
            g.fill(tx, PAD, tx+tw-2, PAD+TAB_H, i==tab ? 0xFF707070 : 0xFF404040);
        }
        super.render(g, mx, my, delta);
        g.drawCenteredString(font, title, width/2, 4, 0xFFFFFF);
        for (Label l : labels) g.drawString(font, l.text, PAD+10, l.y, 0xE0E0E0);
    }

    @Override public void onClose() { if (minecraft != null && parent != null) minecraft.setScreen(parent); }

    public static String buildReport() {
        var sb = new StringBuilder("=== Kaiten Diagnostic Report ===\n");
        sb.append("Time: ").append(java.time.Instant.now()).append("\n\n");
        try { var d = net.vulkanmod.vulkan.device.DeviceManager.device; sb.append("GPU: ").append(d!=null?d.deviceName:"?").append("\n"); } catch (Throwable t) {}
        sb.append("SL init: ").append(NativeBridge.isStreamlineInitialized()).append("\n");
        sb.append("SR: ").append(NativeBridge.dlssSupported).append(" FG: ").append(NativeBridge.frameGenSupported)
          .append(" FGMax: ").append(NativeBridge.frameGenMaxMultiplier+1).append("x Reflex: ").append(NativeBridge.reflexSupported).append("\n");
        var p = KaitenConfig.INSTANCE.getActiveProfile();
        if (p != null) sb.append("Profile: ").append(p.name).append(" SR:").append(p.dlssEnabled)
                .append(" FG:").append(p.fgEnabled?p.fgMultiplier+1+"x":"Off").append(" Reflex:").append(p.reflexMode).append("\n");
        sb.append("Java: ").append(System.getProperty("java.version")).append(" OS: ").append(System.getProperty("os.name")).append("\n");
        return sb.toString();
    }

    private static String onOff(boolean b) { return b ? "ON" : "OFF"; }
    private static String check(boolean b) { return b ? "\u2714" : "\u2718"; }
    private record Label(String text, int y) {}
}
