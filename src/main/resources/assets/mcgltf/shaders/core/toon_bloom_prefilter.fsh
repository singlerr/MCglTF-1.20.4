#version 330

uniform sampler2D ToonSource;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    vec4 color = texture(ToonSource, texCoord);
    float coverage = clamp(color.a, 0.0, 1.0);
    vec3 straight = coverage > 0.000001 ? color.rgb / coverage : vec3(0.0);
    color.rgb = max(straight - vec3(1.0), vec3(0.0)) * coverage;
    color.a = coverage;
    fragColor = color;
}
