#version 150

#moj_import <fog.glsl>

in vec3 Position;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec4 Color;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform int FogShape;

uniform vec3 GroupOffset;
uniform float SkyBrightness;
uniform float AmbientLight;
uniform float TintIntensity;

out float vertexDistance;
out vec2 texCoord0;
out vec4 vertexColor;
out vec3 modelColor;
out float tintWeight;
out float lightStrength;

void main() {
    vec3 pos = Position + GroupOffset;
    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);
    vertexDistance = fog_distance(pos, FogShape);
    texCoord0 = UV0;
    float sky = clamp(float(UV2.y) / 240.0, 0.0, 1.0);
    float skyLight = mix(sky / (4.0 - 3.0 * sky), 1.0, AmbientLight) * SkyBrightness;
    float visibility = 1.0 - clamp(skyLight, 0.0, 1.0);
    // UV1 stores model RGB/alpha independently of the propagated light hue in Color.
    modelColor = vec3((UV1.x >> 8) & 255, UV1.x & 255, UV1.y & 255) / 255.0;
    float modelAlpha = float((UV1.y >> 8) & 255) / 255.0;
    float saturation = 1.0 - min(Color.r, min(Color.g, Color.b));
    float tintVisibility = visibility + (1.0 - visibility) * 0.25 * saturation;
    float blockLight = clamp(float(UV2.x) / 240.0, 0.0, 1.0);
    float strength = Color.a / max(modelAlpha, 1.0 / 255.0);
    lightStrength = strength;
    float share = clamp(strength / max(1.0 / 15.0, max(blockLight, skyLight)), 0.0, 1.0);
    tintWeight = TintIntensity * share * tintVisibility * modelAlpha;
    vertexColor = vec4(Color.rgb, Color.a * visibility);
}
