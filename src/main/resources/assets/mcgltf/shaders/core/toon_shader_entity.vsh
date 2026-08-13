#version 330

#moj_import <minecraft:sample_lightmap.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Normal;
in uint LineWidth;

uniform sampler2D OutlineWidthTexture;
uniform sampler2D MinecraftLightmap;
uniform sampler2D SceneDepth;

layout(std140) uniform ToonMaterial {
    vec4 BaseColor;
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
    vec4 MainLightDirection;
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
out vec4 viewTangent;

vec3 octDecode(vec2 encoded) {
    vec3 value = vec3(encoded, 1.0 - abs(encoded.x) - abs(encoded.y));
    if (value.z < 0.0) {
        value.xy = (1.0 - abs(value.yx))
            * vec2(value.x < 0.0 ? -1.0 : 1.0, value.y < 0.0 ? -1.0 : 1.0);
    }
    return normalize(value);
}

vec4 decodeTangent(ivec2 packedValue) {
    uint x = uint(packedValue.x) & 0xFFFFu;
    uint y = uint(packedValue.y) & 0xFFFFu;
    vec2 oct = vec2(x & 0x7FFFu, y & 0x7FFFu) / 32767.0 * 2.0 - 1.0;
    return vec4(octDecode(oct), (x & 0x8000u) == 0u ? 1.0 : -1.0);
}

int signed16(uint value) {
    return value >= 0x8000u ? int(value) - 0x10000 : int(value);
}

vec3 decodeSmoothNormal(uint packedValue) {
    vec2 oct = clamp(vec2(signed16(packedValue & 0xFFFFu), signed16(packedValue >> 16u)) / 32767.0,
        vec2(-1.0), vec2(1.0));
    return octDecode(oct);
}

float outlineWidth(float viewZ) {
    float mask = textureLod(OutlineWidthTexture, UV0, 0.0).g;
    if (Flags.z > 0.5) {
        mask *= Color.a;
    }
    bool profiled = HeadForward.w > 0.5;
    if (!profiled && Flags.y < 0.5) {
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
    vec4 tangent = decodeTangent(UV2);
    vec3 smoothNormal = decodeSmoothNormal(LineWidth);
#ifdef TOON_SHADER_OUTLINE
    vec2 outlineDirection = smoothNormal.xy;
    float outlineLength = length(outlineDirection);
    vec3 outlineNormal = outlineLength > 0.0001
        ? vec3(outlineDirection / outlineLength, 0.0) : vec3(0.0);
    vec3 viewDirection = length(position) > 0.0001 ? normalize(position) : vec3(0.0, 0.0, -1.0);
    position += 0.01 * OutlineParams.y * viewDirection;
    if (Flags.y < 0.5) {
        position += outlineWidth(position.z) * outlineNormal;
    }
#endif
    gl_Position = ToonProjectionMatrix * vec4(position, 1.0);
#ifdef TOON_SHADER_OUTLINE
    if (Flags.y > 0.5) {
        float width = outlineWidth(position.z);
        if (HeadForward.w > 0.5) {
            vec2 viewport = vec2(textureSize(SceneDepth, 0));
            vec2 pixelDirection = outlineNormal.xy
                * vec2(ToonProjectionMatrix[0][0], ToonProjectionMatrix[1][1]) * viewport;
            pixelDirection /= max(length(pixelDirection), 0.0001);
            gl_Position.xy += 200.0 * width * pixelDirection / viewport * gl_Position.w;
        } else {
            gl_Position.xy += 2.0 * width * outlineNormal.xy * gl_Position.w;
        }
    }
    gl_Position.xy += ScreenOffset.zw * gl_Position.w;
#else
    gl_Position.xy += ScreenOffset.xy * gl_Position.w;
#endif
    vertexColor = Color;
    lightMapColor = sample_lightmap(MinecraftLightmap, ivec2(round(Flags2.zw)));
    texCoord0 = UV0;
    backTexCoord = vec2(UV1) / 32767.0;
    viewPosition = position;
    viewNormal = normal;
    viewTangent = tangent;
}
