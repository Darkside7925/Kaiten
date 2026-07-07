package net.kaiten.iris;

import net.vulkanmod.vulkan.shader.SPIRVUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Milestone-1 driver for the Vulkan Iris port: load a real shaderpack, resolve its
 * {@code #include}s, run the minimal GLSL→Vulkan transform, and attempt SPIR-V compilation
 * via VulkanMod's existing shaderc bridge — reporting per-program success/failure.
 *
 * <p>This proves the front half of a Vulkan shader pipeline end-to-end (pack on disk →
 * SPIR-V) and precisely measures the GLSL-transform gap a full port must still close.
 * It does NOT render anything yet — that's a later milestone once terrain hooking and
 * render-pass wiring exist.
 */
public final class IrisShaderLoader {
    private static final Logger LOGGER = LogManager.getLogger("VulkanMod-Iris");
    private IrisShaderLoader() {}

    public record CompileReport(int programs, int vertexOk, int fragmentOk, List<String> failures) {}

    /** Load {@code shaderpacksDir/<name>} and try to compile every program. Logs a summary. */
    public static CompileReport loadAndCompile(Path shaderpacksDir, String name) {
        LOGGER.info("[Iris] === Loading shaderpack '{}' ===", name);
        ShaderPack pack = ShaderPack.load(shaderpacksDir, name);
        if (pack == null) {
            return new CompileReport(0, 0, 0, List.of("pack not found or unreadable"));
        }

        int vOk = 0, fOk = 0, total = 0;
        List<String> failures = new ArrayList<>();

        try {
            for (ShaderPack.Program program : pack.programs()) {
                total++;

                // Vertex
                try {
                    String vGlsl = GlslToVulkan.transform(program.vertexSource(), GlslToVulkan.Stage.VERTEX);
                    SPIRVUtils.compileShader(program.id() + ".vsh", vGlsl, SPIRVUtils.ShaderKind.VERTEX_SHADER);
                    vOk++;
                } catch (Throwable t) {
                    failures.add(program.id() + ".vsh: " + firstError(t));
                }

                // Fragment
                try {
                    String fGlsl = GlslToVulkan.transform(program.fragmentSource(), GlslToVulkan.Stage.FRAGMENT);
                    SPIRVUtils.compileShader(program.id() + ".fsh", fGlsl, SPIRVUtils.ShaderKind.FRAGMENT_SHADER);
                    fOk++;
                } catch (Throwable t) {
                    failures.add(program.id() + ".fsh: " + firstError(t));
                }
            }
        } finally {
            pack.close();
        }

        LOGGER.info("[Iris] === Compile summary for '{}' ===", name);
        LOGGER.info("[Iris]   programs found : {}", total);
        LOGGER.info("[Iris]   vertex   SPIR-V: {}/{}", vOk, total);
        LOGGER.info("[Iris]   fragment SPIR-V: {}/{}", fOk, total);
        if (!failures.isEmpty()) {
            LOGGER.info("[Iris]   {} stage(s) need deeper GLSL transform (legacy builtins). Examples:", failures.size());
            failures.stream().limit(8).forEach(f -> LOGGER.info("[Iris]     - {}", f));
        }
        return new CompileReport(total, vOk, fOk, failures);
    }

    /** Convenience: default shaderpacks dir under the game run directory. */
    public static Path defaultShaderpacksDir() {
        try {
            Path gameDir = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir();
            Path dir = gameDir.resolve("shaderpacks");
            Files.createDirectories(dir);
            return dir;
        } catch (Throwable t) {
            return Path.of("shaderpacks");
        }
    }

    /** List available packs (folders with a shaders/ dir, or .zip files). */
    public static List<String> listPacks(Path shaderpacksDir) {
        List<String> out = new ArrayList<>();
        try (var s = Files.list(shaderpacksDir)) {
            s.forEach(p -> {
                String fn = p.getFileName().toString();
                if (Files.isDirectory(p) && Files.isDirectory(p.resolve("shaders"))) out.add(fn);
                else if (fn.endsWith(".zip")) out.add(fn);
            });
        } catch (Throwable ignored) {}
        return out;
    }

    private static String firstError(Throwable t) {
        String msg = t.getMessage();
        if (msg == null) return t.getClass().getSimpleName();
        // shaderc dumps multi-line errors; keep the first meaningful line.
        for (String line : msg.split("\n")) {
            String s = line.strip();
            if (s.isEmpty()) continue;
            if (s.startsWith("Failed to compile")) continue;
            return s.length() > 160 ? s.substring(0, 160) + "…" : s;
        }
        return msg.length() > 160 ? msg.substring(0, 160) + "…" : msg;
    }
}
