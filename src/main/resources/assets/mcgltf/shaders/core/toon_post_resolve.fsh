#version 330

uniform sampler2D ToonHdr;
uniform sampler2D BloomTexture;

in vec2 texCoord;

out vec4 fragColor;

vec3 linearToSrgb(vec3 color) {
    color = max(color, vec3(0.0));
    vec3 low = color * 12.92;
    vec3 high = 1.055 * pow(color, vec3(1.0 / 2.4)) - 0.055;
    return mix(low, high, step(vec3(0.0031308), color));
}

vec3 officialDisplay(vec3 color) {
    color *= 1.05;
    vec3 numerator = (1.36 * color + 0.047) * color;
    vec3 denominator = (0.93 * color + 0.56) * color + 0.14;
    return linearToSrgb(clamp(numerator / denominator, vec3(0.0), vec3(1.0)));
}

void main() {
	vec4 toon = texture(ToonHdr, texCoord);
	float coverage = clamp(toon.a, 0.0, 1.0);
#ifdef TOON_NO_BLOOM
	vec4 bloomSample = vec4(0.0);
#else
	vec4 bloomSample = texture(BloomTexture, texCoord);
#endif
	if (max(coverage, bloomSample.a) <= 0.000001) {
		discard;
	}
	vec3 bloom = bloomSample.rgb * 1.5;
	vec3 straightToon = coverage > 0.000001 ? toon.rgb / coverage : vec3(0.0);
	vec3 character = officialDisplay(straightToon + bloom);
	vec3 halo = officialDisplay(bloom) * (1.0 - coverage);
	fragColor = vec4(character * coverage + halo, coverage);
}
