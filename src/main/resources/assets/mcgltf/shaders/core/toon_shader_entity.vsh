#version 330

#moj_import <minecraft:sample_lightmap.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Normal;

uniform sampler2D OutlineWidthTexture;
uniform sampler2D MinecraftLightmap;

layout(std140) uniform ToonMaterial {
    vec4 ShadeColor;
    vec4 EmissionColor;
    vec4 RimColor;
    vec4 OutlineColor1;
    vec4 OutlineColor2;
    vec4 OutlineColor3;
    vec4 OutlineColor4;
    vec4 OutlineColor5;
    vec4 BlushColor;
    vec4 ScreenOffset;
    vec4 ShadowParams;
    vec4 MaterialParams;
    vec4 SpecularParams;
    vec4 RimParams;
    vec4 OutlineParams;
    vec4 OutlineWidthParams;
    vec4 FaceParams;
    vec4 Flags;
    vec4 Flags2;
    vec4 HeadForward;
    vec4 HeadRight;
    vec4 LightDirectionMultiplier;
};

layout(std140) uniform ToonProjection {
    mat4 ToonProjectionMatrix;
};

out vec4 vertexColor;
out vec4 lightMapColor;
out vec2 texCoord0;
out vec2 backTexCoord;
out vec3 viewPosition;
out vec3 viewNormal;

float outlineWidth(float viewZ) {
    float mask = textureLod(OutlineWidthTexture, UV0, 0.0).g;
    if (Flags.z > 0.5) {
        mask *= Color.a;
    }
    if (Flags.y < 0.5) {
        return 0.01 * OutlineParams.x * mask;
    }
    float fovFactor = 2.414 / max(abs(ToonProjectionMatrix[1][1]), 0.0001);
    float z = abs(viewZ * fovFactor);
    float denominator = max(OutlineWidthParams.y - OutlineWidthParams.x, 0.0001);
    float k = clamp((z - OutlineWidthParams.x) / denominator, 0.0, 1.0);
    return 0.01 * OutlineParams.x * mix(OutlineWidthParams.z, OutlineWidthParams.w, k) * mask;
}

void main() {
	vec3 position = Position;
    vec3 normal = normalize(Normal);
#ifdef TOON_SHADER_OUTLINE
    vec3 outlineNormal = normalize(vec3(normal.xy, 0.0));
    if (length(outlineNormal.xy) < 0.0001) {
        outlineNormal = vec3(1.0, 0.0, 0.0);
    }
    vec3 viewDirection = length(position) > 0.0001 ? normalize(position) : vec3(0.0, 0.0, -1.0);
    position += 0.01 * OutlineParams.y * viewDirection;
    position += outlineWidth(position.z) * outlineNormal;
#endif
    gl_Position = ToonProjectionMatrix * vec4(position, 1.0);
#ifdef TOON_SHADER_OUTLINE
    gl_Position.xy += ScreenOffset.zw * gl_Position.w;
#else
    gl_Position.xy += ScreenOffset.xy * gl_Position.w;
#endif
    vertexColor = Color;
    lightMapColor = sample_lightmap(MinecraftLightmap, UV2);
    texCoord0 = UV0;
    backTexCoord = vec2(UV1) / 32767.0;
    viewPosition = position;
    viewNormal = normal;
}
