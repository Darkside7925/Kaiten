package net.kaiten.iris;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Resolves OptiFine/Iris {@code #include} directives in shaderpack GLSL.
 *
 * <p>Semantics (matching Iris/OptiFine):
 * <ul>
 *   <li>{@code #include "path"} — path relative to the including file's directory, OR
 *       absolute from the pack's {@code shaders/} root if it starts with {@code /}.</li>
 *   <li>Includes are inlined recursively; cycles are detected and rejected.</li>
 *   <li>Line directives are preserved conceptually (we keep line count stable by
 *       replacing the {@code #include} line with the included content).</li>
 * </ul>
 *
 * <p>This is a faithful, self-contained reimplementation of Iris's IncludeProcessor +
 * IncludeGraph, without dragging in Iris's AbsolutePackPath/FileNode graph classes
 * (which are coupled to the rest of the mod).
 */
final class IncludeResolver {
    private final ShaderPack.PackSource source;

    IncludeResolver(ShaderPack.PackSource source) {
        this.source = source;
    }

    /** Resolve all includes in {@code rootSource}, whose pack-relative path is {@code rootPath}. */
    String resolve(String rootPath, String rootSource) {
        StringBuilder out = new StringBuilder(rootSource.length() * 2);
        Deque<String> stack = new ArrayDeque<>();
        process(rootPath, rootSource, out, stack);
        return out.toString();
    }

    private void process(String path, String src, StringBuilder out, Deque<String> stack) {
        if (stack.contains(path)) {
            throw new IllegalStateException("#include cycle detected at " + path + " (stack: " + stack + ")");
        }
        stack.push(path);
        try {
            String[] lines = src.split("\n", -1);
            for (String line : lines) {
                String trimmed = line.strip();
                if (trimmed.startsWith("#include")) {
                    String included = parseIncludeTarget(trimmed);
                    String resolvedPath = resolvePath(path, included);
                    String content = source.readOrNull(resolvedPath);
                    if (content == null) {
                        throw new IllegalStateException("#include target not found: '" + included
                                + "' (resolved to " + resolvedPath + ") from " + path);
                    }
                    process(resolvedPath, content, out, stack);
                    out.append('\n');
                } else {
                    out.append(line).append('\n');
                }
            }
        } finally {
            stack.pop();
        }
    }

    /** Extracts the path from {@code #include "foo/bar.glsl"} or {@code #include foo/bar.glsl}. */
    private static String parseIncludeTarget(String directive) {
        String rest = directive.substring("#include".length()).strip();
        if (rest.startsWith("\"")) {
            int end = rest.indexOf('"', 1);
            if (end < 0) throw new IllegalStateException("Malformed #include: " + directive);
            return rest.substring(1, end);
        }
        // Unquoted form: take up to whitespace/comment
        int cut = rest.length();
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (Character.isWhitespace(c) || c == '/') { cut = i; break; }
        }
        return rest.substring(0, cut);
    }

    /**
     * Resolves an include target against the including file.
     * Absolute ({@code /path}) → relative to {@code shaders/}. Relative → sibling of includer.
     */
    private static String resolvePath(String includerPath, String target) {
        if (target.startsWith("/")) {
            return "shaders" + target; // "/foo.glsl" -> "shaders/foo.glsl"
        }
        int lastSlash = includerPath.lastIndexOf('/');
        String dir = (lastSlash >= 0) ? includerPath.substring(0, lastSlash) : "shaders";
        return normalize(dir + "/" + target);
    }

    /** Collapse {@code a/b/../c} into {@code a/c}. */
    private static String normalize(String path) {
        String[] parts = path.split("/");
        Deque<String> out = new ArrayDeque<>();
        for (String part : parts) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) { if (!out.isEmpty()) out.removeLast(); }
            else out.addLast(part);
        }
        return String.join("/", out);
    }
}
