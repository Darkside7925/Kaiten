package net.kaiten.iris;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal OpenGL-GLSL → Vulkan-GLSL transform.
 *
 * <p>Shaderpacks ship legacy OpenGL GLSL (typically {@code #version 120} or
 * {@code #version 330 compatibility}) that the Vulkan SPIR-V compiler (shaderc, Vulkan
 * target) rejects: Vulkan GLSL forbids the compatibility profile, legacy {@code gl_}
 * builtins, {@code attribute}/{@code varying} keywords, and requires explicit
 * {@code layout(location=)} on every in/out.
 *
 * <p>This class performs the <em>mechanical, always-safe</em> subset of that transform.
 * The deep part — substituting legacy builtins like {@code gl_ModelViewMatrix},
 * {@code gl_Vertex}, {@code gl_MultiTexCoord0} with uniforms/attributes — is what Iris
 * does with the glsl-transformer AST library and is deferred to a later milestone. Here we
 * do the trivial upgrades and let shaderc report the remaining gap precisely, which is the
 * point of milestone 1: <em>measure</em> what a full port must still cover.
 */
public final class GlslToVulkan {
    private GlslToVulkan() {}

    public enum Stage { VERTEX, FRAGMENT, GEOMETRY }

    private static final Pattern VERSION = Pattern.compile("(?m)^\\s*#version\\s+\\d+.*$");

    public static String transform(String glsl, Stage stage) {
        StringBuilder sb = new StringBuilder(glsl.length() + 256);

        // 1. Force a Vulkan-compatible version. Strip the pack's #version line.
        String body = VERSION.matcher(glsl).replaceFirst("");
        sb.append("#version 450\n");

        // 2. Legacy keyword upgrades (GLSL 120 -> modern).
        body = upgradeLegacyKeywords(body, stage);

        // 3. texture2D/texture3D/textureCube -> overloaded texture()
        body = body.replaceAll("\\btexture2D\\b", "texture")
                   .replaceAll("\\btexture3D\\b", "texture")
                   .replaceAll("\\btextureCube\\b", "texture")
                   .replaceAll("\\bshadow2D\\b", "texture");

        sb.append(body);
        return sb.toString();
    }

    private static String upgradeLegacyKeywords(String src, Stage stage) {
        // `attribute` only exists in vertex shaders -> `in`.
        // `varying` -> `out` in vertex, `in` in fragment.
        String out = src;
        if (stage == Stage.VERTEX) {
            out = replaceWord(out, "attribute", "in");
            out = replaceWord(out, "varying", "out");
        } else if (stage == Stage.FRAGMENT) {
            out = replaceWord(out, "varying", "in");
        }
        return out;
    }

    private static String replaceWord(String src, String word, String replacement) {
        return src.replaceAll("\\b" + Pattern.quote(word) + "\\b", Matcher.quoteReplacement(replacement));
    }
}
