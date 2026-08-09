#version 330

#moj_import <minecraft:light.glsl>

uniform sampler2D BaseTexture;
uniform sampler2D ShadeTexture;
uniform sampler2D NormalTexture;
uniform sampler2D EmissionTexture;
uniform sampler2D MatcapTexture;
uniform sampler2D RimTexture;
uniform sampler2D LightMapTexture;
uniform sampler2D FaceMapTexture;
uniform sampler2D RampTexture;
uniform sampler2D SceneColor;
uniform sampler2D SceneDepth;

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

in vec4 vertexColor;
in vec4 lightMapColor;
in vec2 texCoord0;
in vec2 backTexCoord;
in vec3 viewPosition;
in vec3 viewNormal;

out vec4 fragColor;

float linearEyeDepth(float depth) {
    float ndc = depth * 2.0 - 1.0;
    float denominator = ndc * ToonProjectionMatrix[2][3] - ToonProjectionMatrix[2][2];
    float safeDenominator = abs(denominator) < 0.000001
        ? (denominator < 0.0 ? -0.000001 : 0.000001) : denominator;
    float viewZ = (ToonProjectionMatrix[3][2] - ndc * ToonProjectionMatrix[3][3]) / safeDenominator;
    return abs(viewZ);
}

mat3 cotangentFrame(vec3 normal, vec3 position, vec2 uv) {
    vec3 dp1 = dFdx(position);
    vec3 dp2 = dFdy(position);
    vec2 duv1 = dFdx(uv);
    vec2 duv2 = dFdy(uv);
    vec3 dp2perp = cross(dp2, normal);
    vec3 dp1perp = cross(normal, dp1);
    vec3 tangent = dp2perp * duv1.x + dp1perp * duv2.x;
    vec3 bitangent = dp2perp * duv1.y + dp1perp * duv2.y;
    float scale = inversesqrt(max(max(dot(tangent, tangent), dot(bitangent, bitangent)), 0.000001));
    return mat3(tangent * scale, bitangent * scale, normal);
}

float bodyShadow(vec3 normal, vec3 lightDirection, float ao) {
    float halfLambert = 0.5 * dot(normal, lightDirection) + 0.5;
    float shadow = clamp(2.0 * halfLambert * ao, 0.0, 1.0);
    return mix(shadow, 1.0, step(0.9, ao));
}

float faceShadow(vec2 uv, vec3 lightDirection, vec4 faceMap) {
    vec3 forward = normalize(HeadForward.xyz);
    vec3 right = normalize(HeadRight.xyz);
    float forwardDotLight = dot(forward, lightDirection);
    float sideDotLight = dot(right, lightDirection);
    vec2 shadowUv = uv;
    shadowUv.x = mix(shadowUv.x, 1.0 - shadowUv.x, step(0.0, sideDotLight));
    float sdf = texture(FaceMapTexture, shadowUv).r;
    float shadow = step(-0.5 * forwardDotLight + 0.5 + FaceParams.y, sdf);
    return mix(shadow, 1.0, faceMap.g);
}

float materialRow(float encodedMaterial) {
    if (MaterialParams.y >= 0.0) {
        return clamp(MaterialParams.y, 0.0, 4.0);
    }
    float index = 4.0;
    index = mix(index, 1.0, step(0.2, encodedMaterial));
    index = mix(index, 2.0, step(0.4, encodedMaterial));
    index = mix(index, 0.0, step(0.6, encodedMaterial));
    return mix(index, 3.0, step(0.8, encodedMaterial));
}

vec3 shadowColor(float shadow, float material, float day) {
    float rangeMin = 0.5 + ShadowParams.x - ShadowParams.y;
    float rangeMax = 0.5 + ShadowParams.x;
    vec2 rampUv = vec2(smoothstep(rangeMin, rangeMax, shadow), material / 10.0 + 0.5 * day + 0.05);
    vec3 ramp = texture(RampTexture, rampUv).rgb;
    vec3 color = ramp * mix(ShadeColor.rgb, vec3(1.0), smoothstep(0.9, 1.0, rampUv.x));
    return mix(color, vec3(1.0), step(rangeMax, shadow));
}

vec3 outlineColor(float material) {
    if (material < 0.5) return OutlineColor1.rgb;
    if (material < 1.5) return OutlineColor2.rgb;
    if (material < 2.5) return OutlineColor3.rgb;
    if (material < 3.5) return OutlineColor4.rgb;
    return OutlineColor5.rgb;
}

void main() {
	ivec2 sceneSize = textureSize(SceneDepth, 0);
    vec2 screenUv = gl_FragCoord.xy / vec2(sceneSize);
    float sceneEyeDepth = linearEyeDepth(texture(SceneDepth, screenUv).r);
    float currentEyeDepth = abs(viewPosition.z);
    if (currentEyeDepth > sceneEyeDepth + max(0.002, currentEyeDepth * 0.0002)) {
        discard;
    }

    vec2 uv = texCoord0;
    if (!gl_FrontFacing && Flags2.x > 0.5 && Flags.w > 0.5) {
        uv = backTexCoord;
    }
    vec4 baseMap = texture(BaseTexture, uv) * vertexColor;
    if (baseMap.a < Flags.x) {
        discard;
    }
    vec4 lightMap = texture(LightMapTexture, uv);
    float material = materialRow(lightMap.a);

#ifdef TOON_SHADER_OUTLINE
    if (gl_FrontFacing) {
        discard;
    }
    vec3 color = outlineColor(material);
    color *= mix(vec3(1.0), lightMapColor.rgb, OutlineParams.z);
    fragColor = vec4(color, 1.0);
    return;
#endif

    vec3 normal = normalize(viewNormal);
    vec3 mappedNormal = texture(NormalTexture, uv).xyz * 2.0 - 1.0;
    normal = normalize(cotangentFrame(normal, viewPosition, uv) * mappedNormal);
    vec3 viewDirection = normalize(-viewPosition);
    vec3 combinedLight = (Light0_Direction + Light1_Direction) * LightDirectionMultiplier.xyz;
    vec3 lightDirection = length(combinedLight) > 0.0001 ? normalize(combinedLight) : vec3(0.0, 1.0, 0.0);
    vec4 faceMap = texture(FaceMapTexture, uv);
    bool facePixel = SpecularParams.w > 0.5 && faceMap.a > 0.001;
    float shadow = facePixel
        ? faceShadow(uv, lightDirection, faceMap)
        : bodyShadow(normal, lightDirection, lightMap.g);
    if (facePixel) {
        shadow = mix(1.0, shadow, FaceParams.x);
    }
    vec3 albedo = baseMap.rgb;
    if (facePixel) {
        albedo = mix(albedo, BlushColor.rgb, FaceParams.z * faceMap.b);
    }
    vec3 shadeAlbedo = texture(ShadeTexture, uv).rgb * vertexColor.rgb;
    vec3 shaded = mix(shadeAlbedo, albedo, shadow) * shadowColor(shadow, material, 1.0 - Flags2.y);

    vec3 halfDirection = normalize(lightDirection + viewDirection);
    float blinnPhong = pow(max(dot(normal, halfDirection), 0.0), max(SpecularParams.x, 0.0001));
    vec3 matcap = texture(MatcapTexture, normal.xy * 0.5 + 0.5).rgb;
    vec3 nonMetallic = vec3(step(1.1, lightMap.b + blinnPhong) * lightMap.r * MaterialParams.z);
    vec3 metallic = blinnPhong * lightMap.b * albedo * matcap * MaterialParams.w;
    float metalSelector = max(step(0.9, lightMap.r), SpecularParams.z);
    vec3 specular = mix(nonMetallic, metallic, metalSelector);

    vec3 emissionMask = texture(EmissionTexture, uv).rgb * EmissionColor.rgb;
    vec3 emission = albedo * SpecularParams.y * baseMap.a * emissionMask;

    vec2 rimUv = clamp(screenUv + vec2(RimParams.x * normal.x / float(sceneSize.x), 0.0),
        vec2(0.0), vec2(1.0));
    float offsetEyeDepth = linearEyeDepth(texture(SceneDepth, rimUv).r);
    float depthRim = smoothstep(0.0, max(RimParams.y, 0.0001), offsetEyeDepth - currentEyeDepth) * RimParams.z;
    float fresnel = pow(clamp(1.0 - dot(normal, viewDirection), 0.0, 1.0), max(RimParams.w, 0.0001));
    vec3 rim = albedo * depthRim * fresnel * texture(RimTexture, uv).r * RimColor.rgb;

    vec3 ambient = mix(lightMapColor.rgb, vec3(1.0), clamp(MaterialParams.x, 0.0, 1.0));
    vec3 finalColor = shaded * ambient + specular + rim + emission;
    vec3 sceneColor = texture(SceneColor, screenUv).rgb;
    fragColor = vec4(mix(sceneColor, finalColor, baseMap.a), 1.0);
}
