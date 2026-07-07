package net.kaiten.iris;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * A loaded OptiFine/Iris-format shader pack, opened from a folder or a .zip.
 *
 * <p>This is the pure-data foundation of the Vulkan Iris port (milestone 1): it discovers
 * the pack root (the folder containing {@code shaders/}), reads {@code shaders.properties},
 * and exposes the raw GLSL source of each program (e.g. {@code gbuffers_basic},
 * {@code composite}, {@code final}) with {@code #include} directives resolved.
 *
 * <p>No OpenGL, no Sodium, no rendering — this stage answers "can we read a real shaderpack
 * and get compilable GLSL out?" independent of the rest of the pipeline. Later milestones
 * (GLSL→Vulkan transform, SPIR-V compile, render-pass wiring) build on this.
 */
public final class ShaderPack {
    private static final Logger LOGGER = LogManager.getLogger("VulkanMod-Iris");

    /** The canonical set of gbuffer program names an OptiFine/Iris pack may provide. */
    public static final String[] GBUFFER_PROGRAMS = {
            "gbuffers_basic", "gbuffers_textured", "gbuffers_textured_lit",
            "gbuffers_skybasic", "gbuffers_skytextured", "gbuffers_clouds",
            "gbuffers_terrain", "gbuffers_terrain_solid", "gbuffers_terrain_cutout",
            "gbuffers_damagedblock", "gbuffers_water", "gbuffers_block",
            "gbuffers_beaconbeam", "gbuffers_item", "gbuffers_entities",
            "gbuffers_armor_glint", "gbuffers_spidereyes", "gbuffers_hand",
            "gbuffers_weather", "gbuffers_shadow"
    };

    private final String name;
    private final PackSource source;          // folder or zip access
    private final Properties properties;      // shaders.properties (may be empty)
    private final Map<String, Program> programs = new LinkedHashMap<>();

    private ShaderPack(String name, PackSource source, Properties properties) {
        this.name = name;
        this.source = source;
        this.properties = properties;
    }

    public String name() { return name; }
    public Properties properties() { return properties; }
    public Collection<Program> programs() { return programs.values(); }
    public Program program(String id) { return programs.get(id); }

    /** A single shader program: its resolved vertex + fragment (+ optional geometry) GLSL. */
    public record Program(String id, String vertexSource, String fragmentSource, String geometrySource) {
        public boolean hasGeometry() { return geometrySource != null; }
    }

    /**
     * Load a pack from {@code shaderpacksDir/name} (folder) or {@code shaderpacksDir/name.zip}.
     * Returns null (with a logged reason) if the pack can't be found or has no {@code shaders/} dir.
     */
    public static ShaderPack load(Path shaderpacksDir, String name) {
        try {
            PackSource source = PackSource.open(shaderpacksDir, name);
            if (source == null) {
                LOGGER.warn("[Iris] shaderpack '{}' not found under {}", name, shaderpacksDir);
                return null;
            }

            // shaders.properties (optional)
            Properties props = new Properties();
            String propsText = source.readOrNull("shaders/shaders.properties");
            if (propsText != null) {
                props.load(new java.io.StringReader(propsText));
            }

            ShaderPack pack = new ShaderPack(name, source, props);
            pack.discoverPrograms();
            LOGGER.info("[Iris] Loaded shaderpack '{}' ({} programs) from {}",
                    name, pack.programs.size(), source.describe());
            return pack;
        } catch (Throwable t) {
            LOGGER.error("[Iris] Failed to load shaderpack '{}': {}", name, t.toString());
            return null;
        }
    }

    private void discoverPrograms() {
        IncludeResolver resolver = new IncludeResolver(source);
        for (String id : GBUFFER_PROGRAMS) {
            String vshPath = "shaders/" + id + ".vsh";
            String fshPath = "shaders/" + id + ".fsh";
            String gshPath = "shaders/" + id + ".gsh";

            String vRaw = source.readOrNull(vshPath);
            String fRaw = source.readOrNull(fshPath);
            if (vRaw == null || fRaw == null) continue; // program not provided by this pack

            try {
                String v = resolver.resolve(vshPath, vRaw);
                String f = resolver.resolve(fshPath, fRaw);
                String gRaw = source.readOrNull(gshPath);
                String g = (gRaw != null) ? resolver.resolve(gshPath, gRaw) : null;
                programs.put(id, new Program(id, v, f, g));
            } catch (Throwable t) {
                LOGGER.warn("[Iris]   program '{}' include-resolution failed: {}", id, t.toString());
            }
        }
    }

    public void close() {
        try { source.close(); } catch (IOException ignored) {}
    }

    // ---- Pack source abstraction: folder or zip ----

    interface PackSource extends AutoCloseable {
        /** Reads a pack-relative text file, or null if absent. */
        String readOrNull(String relPath);
        String describe();
        @Override void close() throws IOException;

        static PackSource open(Path shaderpacksDir, String name) throws IOException {
            Path folder = shaderpacksDir.resolve(name);
            if (Files.isDirectory(folder) && Files.isDirectory(folder.resolve("shaders"))) {
                return new FolderSource(folder);
            }
            Path zip = shaderpacksDir.resolve(name.endsWith(".zip") ? name : name + ".zip");
            if (Files.isRegularFile(zip)) {
                return new ZipSource(zip);
            }
            // Allow passing a name that already includes ".zip"
            if (Files.isDirectory(folder)) return new FolderSource(folder);
            return null;
        }
    }

    static final class FolderSource implements PackSource {
        private final Path root;
        FolderSource(Path root) { this.root = root; }
        @Override public String readOrNull(String relPath) {
            try {
                Path p = root.resolve(relPath);
                if (!Files.isRegularFile(p)) return null;
                return Files.readString(p, StandardCharsets.UTF_8);
            } catch (IOException e) { return null; }
        }
        @Override public String describe() { return "folder:" + root; }
        @Override public void close() {}
    }

    static final class ZipSource implements PackSource {
        private final FileSystem fs;
        private final String describe;
        ZipSource(Path zip) throws IOException {
            this.fs = FileSystems.newFileSystem(zip, (ClassLoader) null);
            this.describe = "zip:" + zip.getFileName();
        }
        @Override public String readOrNull(String relPath) {
            try {
                // Packs sometimes nest shaders/ inside a top-level folder; try both.
                Path direct = fs.getPath("/" + relPath);
                if (Files.isRegularFile(direct))
                    return new String(Files.readAllBytes(direct), StandardCharsets.UTF_8);
                return findNested(relPath);
            } catch (IOException e) { return null; }
        }
        private String findNested(String relPath) throws IOException {
            try (var stream = Files.walk(fs.getPath("/"))) {
                Optional<Path> hit = stream.filter(Files::isRegularFile)
                        .filter(p -> p.toString().replace('\\', '/').endsWith("/" + relPath))
                        .findFirst();
                if (hit.isPresent())
                    return new String(Files.readAllBytes(hit.get()), StandardCharsets.UTF_8);
            }
            return null;
        }
        @Override public String describe() { return describe; }
        @Override public void close() throws IOException { fs.close(); }
    }
}
