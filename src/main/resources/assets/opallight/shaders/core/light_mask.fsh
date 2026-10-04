#version 150

#moj_import <fog.glsl>

uniform sampler2D Sampler0;

uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;

uniform float TransitionWeight;
uniform float MaskIntensity;
uniform float EdgeFadeStrength;
uniform int TintPass;

in float vertexDistance;
in vec2 texCoord0;
in vec4 vertexColor;
in vec3 modelColor;
in float tintWeight;
in float lightStrength;

out vec4 fragColor;

void main() {
    float fogFade = mix(1.0, linear_fog_fade(vertexDistance, FogStart, FogEnd), FogColor.a);
    if (fogFade <= 0.0) discard;
    vec4 texColor = texture(Sampler0, texCoord0);
    if (texColor.a < 0.1) discard;
    // Apply the edge envelope per fragment so large faces also fade smoothly.
    float edgeOpacity = smoothstep(0.0, EdgeFadeStrength, lightStrength);
    if (TintPass == 1) {
        float tint = clamp(tintWeight * edgeOpacity * texColor.a * fogFade, 0.0, 1.0);
        vec3 filterColor = mix(vec3(1.0), vertexColor.rgb, tint);
        fragColor = vec4(pow(filterColor, vec3(TransitionWeight)), 1.0);
        return;
    }
    float opacity = clamp(vertexColor.a * MaskIntensity * edgeOpacity * texColor.a * fogFade, 0.0, 1.0);
    // Weight opacity so unchanged light stays stable while old/new meshes crossfade.
    opacity = 1.0 - pow(1.0 - opacity, TransitionWeight);
    fragColor = vec4(texColor.rgb * modelColor * vertexColor.rgb, opacity);
}
