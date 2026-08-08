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
in vec3 toonView;

out vec4 fragColor;

void main() {
    vec4 base = texture(Sampler0, texCoord0) * vertexColor * ColorModulator;
#ifdef ALPHA_CUTOUT
    if (base.a < ALPHA_CUTOUT) {
        discard;
    }
#endif
    vec4 shade = texture(Sampler1, texCoord0);
    float toonControl = floor(shade.a * 255.0 + 0.5);
    float toony = floor(toonControl / 32.0) / 7.0;
    float shift = clamp((floor(mod(toonControl, 32.0) / 4.0) - 3.0) / 3.0, -1.0, 1.0);
    float rimExponent = exp2(mod(toonControl, 4.0));
    shade.rgb *= vertexColor.rgb * ColorModulator.rgb;
    shade.a = base.a;

    vec3 normal = normalize(toonNormal);
    vec3 lightDirection = normalize(Light0_Direction + Light1_Direction);
    float diffuse = dot(normal, lightDirection);
    float feather = max(0.04, 0.62 * (1.0 - toony));
    float ramp = smoothstep(-feather, feather, diffuse + shift);
    vec4 color = mix(shade, base, ramp) * lightMapColor;
    float rim = pow(clamp(1.0 - max(dot(normal, normalize(toonView)), 0.0), 0.0, 1.0), rimExponent);
    color.rgb += min(base.rgb * (0.06 * rim), max(vec3(1.0) - color.rgb, vec3(0.0)));
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}
