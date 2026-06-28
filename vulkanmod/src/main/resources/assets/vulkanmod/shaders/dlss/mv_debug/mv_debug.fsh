#version 450

// DLSS motion-vector DEBUG overlay (Phase 2 visualization).
//
// Flat-depth version: visualizes the camera-only motion-vector FIELD at an assumed
// mid-depth plane (no depth-buffer sampling yet). Proves matrix capture + GPU
// reprojection visually: a smooth color gradient when the camera moves, flat grey
// when it is still. Per-object motion vectors arrive once real depth is sampled.

layout(binding = 0) uniform UBO {
    mat4 DlssInvCurrentVP;   // inverse of current frame's P*MV
    mat4 DlssPrevVP;         // previous frame's P*MV
    vec2 DlssMvScale;        // visualization gain
};

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

void main() {
    float depth = 0.5;                       // assumed plane

    vec2 ndc = texCoord * 2.0 - 1.0;
    vec4 clip = vec4(ndc, depth, 1.0);
    vec4 world = DlssInvCurrentVP * clip;
    world /= world.w;

    vec4 prevClip = DlssPrevVP * vec4(world.xyz, 1.0);
    vec2 prevUV = (prevClip.xy / prevClip.w) * 0.5 + 0.5;

    vec2 mv = (prevUV - texCoord) * DlssMvScale;

    // Encode motion into R/G around a neutral grey; B fixed. Amplify for visibility.
    vec2 vis = clamp(mv * 12.0 + 0.5, 0.0, 1.0);
    fragColor = vec4(vis, 0.5, 1.0);
}
