#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:light.glsl>

uniform sampler2D Sampler0;
uniform sampler2D Sampler1;

in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec4 vertexColor;
in vec4 lightMapColor;
in vec2 texCoord0;
in vec3 toonNormal;

out vec4 fragColor;

void main() {
    vec4 base = texture(Sampler0, texCoord0) * vertexColor * ColorModulator;
#ifdef ALPHA_CUTOUT
    if (base.a < ALPHA_CUTOUT) {
        discard;
    }
#endif
    vec4 shade = texture(Sampler1, texCoord0);
    shade.rgb *= vertexColor.rgb * ColorModulator.rgb;
    shade.a = base.a;

    vec3 lightDirection = normalize(Light0_Direction + Light1_Direction);
    float diffuse = max(dot(normalize(toonNormal), lightDirection), 0.0);
    float ramp = smoothstep(0.30, 0.46, diffuse);
    vec4 color = mix(shade, base, ramp) * lightMapColor;
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}
