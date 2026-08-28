#version 330

uniform sampler2D BaseTexture;
uniform sampler2D AlphaTexture;
uniform sampler2D ShadeTexture;
uniform sampler2D NormalTexture;
uniform sampler2D EmissionTexture;
uniform sampler2D MatcapTexture;
uniform sampler2D RimTexture;
uniform sampler2D LightMapTexture;
uniform sampler2D FaceLightMapTexture;
uniform sampler2D FaceShadowTexture;
uniform sampler2D RampTexture;
uniform sampler2D SceneDepth;
uniform sampler2D ToonDepth;

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
    mat4 ToonModelViewMatrix;
    mat4 ToonNormalMatrix;
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
in vec4 viewTangent;

out vec4 fragColor;

vec3 srgbToLinear(vec3 color) {
    vec3 low = color / 12.92;
    vec3 high = pow((color + 0.055) / 1.055, vec3(2.4));
    return mix(low, high, step(vec3(0.04045), color));
}

float linearEyeDepth(float depth) {
    float ndc = depth * 2.0 - 1.0;
    float denominator = ndc * ToonProjectionMatrix[2][3] - ToonProjectionMatrix[2][2];
    float safeDenominator = abs(denominator) < 0.000001
        ? (denominator < 0.0 ? -0.000001 : 0.000001) : denominator;
    float viewZ = (ToonProjectionMatrix[3][2] - ndc * ToonProjectionMatrix[3][3]) / safeDenominator;
    return abs(viewZ);
}

float bodyShadow(vec3 normal, vec3 lightDirection, float ao) {
    float halfLambert = 0.5 * dot(normal, lightDirection) + 0.5;
    float shadow = clamp(2.0 * halfLambert * ao, 0.0, 1.0);
    return mix(shadow, 1.0, step(0.9, ao));
}

float faceShadow(vec2 uv, vec3 lightDirection) {
    vec3 forward = normalize(HeadForward.xyz);
    vec3 right = normalize(HeadRight.xyz);
    vec3 up = normalize(cross(right, forward));
    forward -= up * dot(forward, up);
    lightDirection -= up * dot(lightDirection, up);
    forward = length(forward) > 0.0001 ? normalize(forward) : vec3(0.0, 0.0, -1.0);
    lightDirection = length(lightDirection) > 0.0001 ? normalize(lightDirection) : vec3(0.0);
    float forwardDotLight = dot(forward, lightDirection);
    float crossDirection = dot(cross(forward, lightDirection), up);
    float sdf;
    if (HeadRight.w > 0.5) {
        vec2 directionalSdf = texture(FaceLightMapTexture, uv).rg;
        sdf = mix(directionalSdf.r, directionalSdf.g, step(0.0, crossDirection));
    } else {
        vec2 shadowUv = uv;
        shadowUv.x = mix(shadowUv.x, 1.0 - shadowUv.x, step(0.0, crossDirection));
        sdf = texture(FaceLightMapTexture, shadowUv).r;
    }
    float shadow = step(-0.5 * forwardDotLight + 0.5 + FaceParams.y, sdf);
    float forceLit = texture(FaceShadowTexture, uv).a;
    return mix(shadow, 1.0, forceLit);
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
    // Genshin ramp sheets are authored for Unity, whose V axis runs bottom-up, so the
    // five daylight rows sit at the top of the image. Minecraft samples V top-down,
    // and the row address has to be mirrored for those sheets to land on the right
    // material bands instead of swapping day for night.
    vec2 rampUv = vec2(smoothstep(rangeMin, rangeMax, shadow),
        1.0 - (material / 10.0 + 0.5 * day + 0.05));
    vec3 ramp = srgbToLinear(texture(RampTexture, rampUv).rgb);
    vec3 color = ramp * mix(ShadeColor.rgb, vec3(1.0), smoothstep(0.9, 1.0, rampUv.x));
    return mix(color, vec3(1.0), step(rangeMax, shadow));
}

vec3 outlineColor(float encodedMaterial, float material) {
    if (MaterialParams.y >= 0.0) {
        if (material > 2.5 && material < 3.5) return OutlineColor1.rgb;
        if (material < 0.5) return OutlineColor2.rgb;
        if (material > 1.5 && material < 2.5) return OutlineColor3.rgb;
        if (material > 0.5 && material < 1.5) return OutlineColor4.rgb;
        return OutlineColor5.rgb;
    }
    vec3 color = OutlineColor5.rgb;
    color = mix(color, OutlineColor4.rgb, step(0.2, encodedMaterial));
    color = mix(color, OutlineColor3.rgb, step(0.4, encodedMaterial));
    color = mix(color, OutlineColor2.rgb, step(0.6, encodedMaterial));
    return mix(color, OutlineColor1.rgb, step(0.8, encodedMaterial));
}

void main() {
    ivec2 sceneSize = textureSize(SceneDepth, 0);
    vec2 screenUv = gl_FragCoord.xy / vec2(sceneSize);
#ifndef TOON_SHADER_OUTLINE
    float sceneEyeDepth = linearEyeDepth(texture(SceneDepth, screenUv).r);
    float currentEyeDepth = abs(viewPosition.z);
    if (currentEyeDepth > sceneEyeDepth + max(0.002, currentEyeDepth * 0.002)) {
        discard;
    }
#endif

    vec2 uv = texCoord0;
    if (!gl_FrontFacing && Flags2.x > 0.5 && Flags.w > 0.5) {
        uv = backTexCoord;
    }
	vec4 baseSample = texture(BaseTexture, uv);
	float alpha = OutlineParams.w > 0.5
		? texture(AlphaTexture, uv).a * BaseColor.a * vertexColor.a : 1.0;
#ifndef TOON_SHADER_OUTLINE
	if (alpha < Flags.x) {
		discard;
	}
#endif

#ifdef TOON_SHADER_DEPTH_ONLY
	fragColor = vec4(0.0);
	return;
#endif

	vec4 lightMap = texture(LightMapTexture, uv);
    float material = materialRow(lightMap.a);
    bool profiled = HeadForward.w > 0.5;

#ifdef TOON_SHADER_OUTLINE
    if (gl_FrontFacing) {
        discard;
    }
    vec3 color = outlineColor(lightMap.a, material);
    if (profiled) {
        color = srgbToLinear(color);
    } else {
        color *= mix(vec3(1.0), lightMapColor.rgb, OutlineParams.z);
    }
    fragColor = vec4(color, 1.0);
    return;
#endif

	vec3 normal = normalize(viewNormal);
    vec3 tangent = normalize(viewTangent.xyz - normal * dot(normal, viewTangent.xyz));
    vec3 bitangent = normalize(cross(normal, tangent)) * viewTangent.w;
    vec3 mappedNormal = texture(NormalTexture, uv).xyz * 2.0 - 1.0;
    mappedNormal.xy *= MainLightDirection.w;
    normal = normalize(tangent * mappedNormal.x + bitangent * mappedNormal.y + normal * mappedNormal.z);
	vec3 viewDirection = normalize(-viewPosition);
	vec3 lightDirection = normalize(MainLightDirection.xyz);
	bool facePixel = SpecularParams.w > 0.5;
    float shadow = facePixel
        ? faceShadow(uv, lightDirection)
        : bodyShadow(normal, lightDirection, lightMap.g * vertexColor.r);
    if (facePixel) {
        shadow = mix(1.0, shadow, FaceParams.x);
    }

	vec3 vertexTint = profiled ? vec3(1.0) : vertexColor.rgb;
	vec3 albedo = srgbToLinear(baseSample.rgb) * BaseColor.rgb * vertexTint;
	if (facePixel) {
		// Official face inputs carry the blush mask in the face light map's blue
		// channel; only the legacy single-sheet layout had to fall back to the base
		// texture's alpha, which covers the whole face and cannot localise a blush.
		float blushMask = FaceParams.w > 0.5 ? baseSample.a : texture(FaceLightMapTexture, uv).b;
		albedo = mix(albedo, srgbToLinear(BlushColor.rgb), FaceParams.z * blushMask);
	}
	vec3 rampColor = shadowColor(shadow, material, 1.0 - Flags2.y);
	vec3 shadeAlbedo = srgbToLinear(texture(ShadeTexture, uv).rgb) * BaseColor.rgb * vertexTint;
    vec3 shaded = profiled ? albedo * rampColor : mix(shadeAlbedo, albedo, shadow) * rampColor;

    vec3 halfDirection = normalize(lightDirection + viewDirection);
    float blinnPhong = pow(max(dot(normal, halfDirection), 0.0), max(SpecularParams.x, 0.0001));
    // Matcap sheets are authored with the highlight upwards under Unity's bottom-up
    // V axis, so the lookup is mirrored to keep metal lit from above here.
    vec3 matcap = srgbToLinear(texture(MatcapTexture, vec2(normal.x, -normal.y) * 0.5 + 0.5).rgb);
    vec3 nonMetallic = vec3(step(1.1, lightMap.b + blinnPhong) * lightMap.r * MaterialParams.z);
    vec3 metallic = blinnPhong * lightMap.b * albedo * matcap * MaterialParams.w;
    float metalSelector = max(step(0.9, lightMap.r), SpecularParams.z);
    vec3 specular = mix(nonMetallic, metallic, metalSelector);

	vec3 emissionSample = srgbToLinear(texture(EmissionTexture, uv).rgb);
	vec3 emissionMask = emissionSample * EmissionColor.rgb;
	vec3 emission = albedo * SpecularParams.y
		* (profiled ? vec3(emissionSample.r) : baseSample.a * emissionMask);

	vec2 rimUv = clamp(screenUv + vec2(RimParams.x * normal.x / float(sceneSize.x), 0.0),
		vec2(0.0), vec2(1.0));
	float toonEyeDepth = linearEyeDepth(texture(ToonDepth, screenUv).r);
	float offsetEyeDepth = linearEyeDepth(texture(ToonDepth, rimUv).r);
	float depthRim = smoothstep(0.0, max(RimParams.y, 0.0001), offsetEyeDepth - toonEyeDepth) * RimParams.z;
    float fresnel = pow(clamp(1.0 - dot(normal, viewDirection), 0.0, 1.0), max(RimParams.w, 0.0001));
    vec3 rimMask = profiled ? vec3(1.0) : texture(RimTexture, uv).rrr * RimColor.rgb;
    vec3 rim = albedo * depthRim * fresnel * rimMask;

    vec3 ambient = mix(lightMapColor.rgb, vec3(1.0), clamp(MaterialParams.x, 0.0, 1.0));
    vec3 finalColor = shaded * (profiled ? vec3(1.0) : ambient) + specular + rim + emission;
    fragColor = vec4(finalColor * alpha, alpha);
}
