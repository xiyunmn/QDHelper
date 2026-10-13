package com.xiyunmn.qdhelper.shared.ui.glass

/**
 * SDF lens from ForbidAd4TieBa 90037020861e8d559e07a581c41fde640ebb8e02,
 * based on Kyant0 / KernelSU (Apache-2.0) and WeChat-LiquidGlass (MIT).
 * See resources/licenses. Both materials reuse one bounded, unfiltered page capture;
 * the foreground droplet also refracts a separate display list of native tabs and badges.
 */
internal const val GLASS_LENS_AGSL = """
uniform shader content;
uniform float2 size;
uniform float2 offset;
uniform float radius;
uniform float bevel;
uniform float refraction;
uniform float depth;
uniform float dispersion;
uniform float2 sampleLo;
uniform float2 sampleHi;

float roundedSDF(float2 p, float2 halfSize, float r) {
    float2 q = abs(p) - halfSize + r;
    return length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - r;
}
float2 normalSDF(float2 p, float2 halfSize, float r) {
    float2 q = abs(p) - halfSize + r;
    float2 positive = max(q, float2(0.0));
    if (length(positive) > 0.0001) return sign(p) * positive / length(positive);
    float axis = step(q.y, q.x);
    return sign(p) * float2(axis, 1.0 - axis);
}
half4 samplePage(float2 p) {
    return content.eval(clamp(p, sampleLo, sampleHi));
}
half4 main(float2 coord) {
    float2 halfSize = max(size * 0.5, float2(0.5));
    float2 p = coord + offset - halfSize;
    float sd = roundedSDF(p, halfSize, radius);
    if (bevel <= 0.001 || abs(refraction) <= 0.001 || -sd >= bevel) return samplePage(coord);
    float edge = clamp(1.0 + min(sd, 0.0) / bevel, 0.0, 1.0);
    float amount = (1.0 - sqrt(max(0.0, 1.0 - edge * edge))) * refraction;
    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 grad = normalSDF(p, halfSize, gradRadius) + depth * p / max(length(p), 0.0001);
    grad /= max(length(grad), 0.0001);
    float2 refracted = coord + amount * grad;
    if (dispersion <= 0.001) return samplePage(refracted);
    float intensity = dispersion * (p.x * p.y) / (halfSize.x * halfSize.y);
    float2 spread = amount * grad * intensity;
    half4 red = samplePage(refracted + spread);
    half4 orange = samplePage(refracted + spread * (2.0 / 3.0));
    half4 yellow = samplePage(refracted + spread * (1.0 / 3.0));
    half4 green = samplePage(refracted);
    half4 cyan = samplePage(refracted - spread * (1.0 / 3.0));
    half4 blue = samplePage(refracted - spread * (2.0 / 3.0));
    half4 purple = samplePage(refracted - spread);
    return half4(
        (red.r + orange.r + yellow.r) / 3.5 + purple.r / 7.0,
        orange.g / 7.0 + (yellow.g + green.g + cyan.g) / 3.5,
        (cyan.b + blue.b + purple.b) / 3.0,
        (red.a + orange.a + yellow.a + green.a + cyan.a + blue.a + purple.a) / 7.0
    );
}
"""

/** Plus blending expects premultiplied white; carrying alpha separately avoids a solid white bloom. */
internal const val GLASS_BLOOM_AGSL = """
uniform float alpha;
uniform float radius;
uniform float2 position;
half4 main(float2 coord) {
    float distanceToLight = distance(coord, position);
    half a = half(alpha * (1.0 - smoothstep(radius * 0.5, radius, distanceToLight)));
    return half4(a, a, a, a);
}
"""
