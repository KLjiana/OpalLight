#version 150

#moj_import <fog.glsl>

in vec3 Position;
in vec2 UV0;
in vec4 Color;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform int FogShape;

uniform vec3 GroupOffset;

out float vertexDistance;
out vec2 texCoord0;
out vec4 vertexColor;

void main() {
    vec3 pos = Position + GroupOffset;
    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);
    vertexDistance = fog_distance(pos, FogShape);
    texCoord0 = UV0;
    vertexColor = Color;
}
