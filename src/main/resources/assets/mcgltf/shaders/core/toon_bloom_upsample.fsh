#version 330

uniform sampler2D BloomA;
uniform sampler2D BloomB;
uniform sampler2D BloomC;
uniform sampler2D BloomD;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    fragColor = texture(BloomA, texCoord) * 0.1
        + texture(BloomB, texCoord) * 0.2
        + texture(BloomC, texCoord) * 0.3
        + texture(BloomD, texCoord) * 0.4;
}
