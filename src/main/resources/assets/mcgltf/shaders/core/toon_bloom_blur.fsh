#version 330

uniform sampler2D ToonSource;
uniform sampler2D ToonFull;

in vec2 texCoord;

out vec4 fragColor;

const float OFFSETS[9] = float[](-4.0, -3.0, -2.0, -1.0, 0.0, 1.0, 2.0, 3.0, 4.0);
const float KERNEL[9] = float[](
    0.01621622,
    0.05405405,
    0.12162162,
    0.19459459,
    0.22702703,
    0.19459459,
    0.12162162,
    0.05405405,
    0.01621622
);

void main() {
    vec2 direction = vec2(0.0, 1.0);
#ifdef TOON_HORIZONTAL
    direction = vec2(1.0, 0.0);
#endif
    float radius = float(textureSize(ToonFull, 0).y) / 1080.0;
#ifdef TOON_DOUBLE_RADIUS
    radius *= 2.0;
#endif
    vec2 offset = radius * direction / vec2(textureSize(ToonSource, 0));
    vec4 color = vec4(0.0);
    for (int i = 0; i < 9; i++) {
        color += KERNEL[i] * texture(ToonSource, texCoord + OFFSETS[i] * offset);
    }
    fragColor = color;
}
