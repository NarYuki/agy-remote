// Ambient colour effects: the empty-chat aurora and the header crown.
#include <SwiftUI/SwiftUI_Metal.h>
#include <metal_stdlib>
using namespace metal;

namespace ambient {

/// Cheap lattice hash in [0, 1).
inline float hash21(float2 q) {
    float3 r = fract(float3(q.xyx) * 0.1031);
    r += dot(r, r.yzx + 33.33);
    return fract((r.x + r.y) * r.z);
}

inline float valueNoise(float2 q) {
    float2 cell = floor(q);
    float2 frac01 = q - cell;
    float2 w = frac01 * frac01 * (3.0 - frac01 * 2.0);
    float a = hash21(cell);
    float b = hash21(cell + float2(1, 0));
    float c = hash21(cell + float2(0, 1));
    float d = hash21(cell + float2(1, 1));
    return mix(mix(a, b, w.x), mix(c, d, w.x), w.y);
}

inline float layered(float2 p) {
    const float cs = cos(0.5), sn = sin(0.5);
    float total = 0.0;
    float weight = 0.5;
    for (int k = 0; k < 4; k++) {
        total += weight * valueNoise(p);
        p = float2(cs * p.x - sn * p.y, sn * p.x + cs * p.y) * 2.03 + 3.1;
        weight *= 0.5;
    }
    return clamp((total - 0.47) * 1.25 + 0.5, 0.0, 1.0);
}

inline float3 brandRamp(float x) {
    const float3 stops[4] = {
        float3(0.102, 0.820, 0.722),
        float3(0.220, 0.659, 0.980),
        float3(0.561, 0.420, 1.000),
        float3(1.000, 0.439, 0.620)
    };
    float s = fract(x) * 4.0;
    int i = min(int(floor(s)), 3);
    float f = s - float(i);
    float w = 0.5 - 0.5 * cos(M_PI_F * f);
    return mix(stops[i], stops[(i + 1) % 4], w);
}

inline float3 washed(float3 c) { return c * 0.7 + 0.3; }

inline float segmentDistance(float2 q, float2 from, float2 to) {
    float2 along = to - from;
    float2 rel = q - from;
    float k = saturate(dot(rel, along) / max(dot(along, along), 1e-6));
    return distance(rel, along * k);
}

inline float promptGlyph(float2 p) {
    float d = segmentDistance(p, float2(-0.30, -0.22), float2(-0.05, 0.0));
    d = min(d, segmentDistance(p, float2(-0.05, 0.0), float2(-0.30, 0.22)));
    d = min(d, segmentDistance(p, float2(0.08, 0.22), float2(0.34, 0.22)));
    return d;
}

} // namespace ambient

using namespace ambient;

[[ stitchable ]] half4 aurora(float2 pixel, half4 unused, float4 rect, float clockIn,
                              float tideIn, float presenceIn, float stirIn, float liftIn,
                              float swellIn, float darkIn) {
    const float time = clockIn, tide = tideIn, presence = presenceIn, stir = stirIn;
    const float lift = liftIn, swell = swellIn, dark = darkIn;
    float2 size = max(rect.zw, float2(1.0));
    float2 uv = pixel / size;
    bool isDark = dark > 0.5;
    float3 base = isDark ? float3(0.0) : float3(1.0);

    bool curtainOn = presence > 0.002 && tide > 0.002;
    if (!curtainOn && swell <= 0.002) {
        return half4(half3(base), 1.0h);
    }

    float up = 1.0 - uv.y;
    float x = uv.x * (size.x / size.y);
    float shifted = max(0.0, up - 0.8 * clamp(lift, 0.0, 0.5));
    float headerGap = smoothstep(0.09, 0.33, uv.y);

    float W = layered(float2(1.5 * x + 0.05 * time, 2.1 * shifted - 0.08 * time));
    float Wc = 1.0 - W;

    // Curtain.
    float3 curtain = float3(0.0);
    float cover = 0.0;
    if (curtainOn) {
        float folds = layered(float2(2.2 * x + 1.4 * W, 0.8 * shifted - 0.11 * time + Wc));
        float shiver = 0.05 * stir * sin(8.5 * uv.x + 2.1 * time + 2.8 * Wc);
        float crest = tide * (0.56 + 0.46 * folds) + shiver * tide;
        float softness = 0.1 + 0.3 * tide;
        float body = 1.0 - smoothstep(0.25 * crest, crest + softness, shifted);
        body = body * body * headerGap;
        cover = body * pow(presence, 0.8);
        float tone = 0.4 * uv.x + 0.42 * W + 0.22 * shifted + 0.03 * time - 0.25;
        float3 hue = brandRamp(tone);
        if (!isDark) hue = washed(hue);
        float bright = clamp(0.4 + 0.62 * smoothstep(0.0, max(crest, 0.05), shifted) + 0.15 * stir, 0.0, 1.0);
        curtain = isDark ? mix(float3(0.02, 0.07, 0.09), 0.92 * hue, bright) : hue;
    }

    // Swell waves and glints.
    float3 waves = float3(0.0);
    float waveCover = 0.0;
    float glint = 0.0;
    float3 glintColour = float3(0.0);
    if (swell > 0.002) {
        float fadeIn = smoothstep(0.0, 0.22, swell);
        float lively = 0.35 + 0.65 * swell;
        float highest = -10.0;
        for (int k = 2; k >= 0; k--) {
            float d = float(k);
            float speed = 0.56 + 0.21 * d;
            float wobble = 0.046 * sin((3.0 + 1.4 * d) * uv.x + 2.0 * speed * time + 2.0 * d)
                + 0.021 * cos((6.8 + 2.0 * d) * uv.x - 1.3 * speed * time + 0.6 * d)
                + 0.09 * (layered(float2(2.1 * uv.x + 4.7 * d, 0.24 * time + d)) - 0.5);
            float top = swell * (0.5 + 0.11 * d) + wobble * lively;
            highest = max(highest, top);
            float under = top - up;
            float feather = 0.15 + 0.06 * d;
            float rise = smoothstep(-1.6 * feather, 0.9 * feather, under);
            float fill = rise * rise * (1.0 - 0.62 * smoothstep(0.0, 0.6, under));
            float rim = exp(-1.8 * under * under / (feather * feather));
            float alpha = clamp(fill * (0.46 - 0.11 * d) + rim * (0.12 - 0.03 * d), 0.0, 1.0) * fadeIn;
            float3 c = brandRamp(0.4 * uv.x + 0.17 * d - 0.22 + 0.3 * W + 0.03 * time);
            if (!isDark) c = washed(c);
            waves = waves * (1.0 - 0.35 * alpha) + c * alpha;
            waveCover = waveCover + alpha * (1.0 - waveCover);
        }
        waves *= headerGap;
        waveCover *= headerGap;

        float2 g = float2(x, up) * 30.0 + float2(0.0, -0.3 * time);
        float row = floor(g.y);
        if (fmod(abs(row), 2.0) >= 1.0) g.x += 0.5;
        float2 cell = floor(g);
        float pick = hash21(cell);
        if (pick > 0.86) {
            float2 nudge = (float2(hash21(cell + 17.0), hash21(cell + 31.0)) - 0.5) * 0.3;
            float scale = mix(0.5, 0.95, hash21(cell + 7.0));
            float2 local = (fract(g) - 0.5 - nudge) / scale;
            local.y = -local.y;   // g.y grows upward; the glyph is defined y-down
            float dist = promptGlyph(local) * scale;
            float ink = 1.0 - smoothstep(0.03, 0.055, dist);
            float flicker = 0.35 + 0.65 * (0.5 + 0.5 * cos(1.9 * time + 2.0 * M_PI_F * pick));
            float n = (up - highest + 0.1) / 0.15;
            glint = ink * flicker * exp(-n * n) * fadeIn * 0.5 * headerGap;
            float3 gc = brandRamp(0.4 * uv.x - 0.22 + 0.2 * pick);
            glintColour = isDark ? mix(gc, float3(1.0), 0.55) : 0.72 * gc;
        }
    }

    float3 rgb;
    if (isDark) {
        rgb = curtain * cover * (1.0 - 0.35 * waveCover) + 0.8 * waves + glintColour * glint;
    } else {
        float lower = smoothstep(0.3, 0.62, uv.y);
        rgb = float3(1.0);
        rgb = mix(rgb, curtain, min(0.6, 0.7 * cover * lower));
        if (waveCover > 0.001) {
            float3 avg = waves / max(0.05, waveCover);
            rgb = mix(rgb, avg, min(0.62, 0.62 * waveCover) * (0.35 + 0.65 * lower));
        }
        rgb = mix(rgb, glintColour, min(0.45, 1.1 * glint * (0.25 + 0.75 * lower)));
    }
    return half4(half3(clamp(rgb, 0.0, 1.0)), 1.0h);
}

[[ stitchable ]] half4 crown(float2 pixel, half4 unused, float4 rect, float hue,
                             float drift, float level, float reach, float dark) {
    if (level < 0.001 || reach < 0.001) return half4(0.0h);
    float2 size = max(rect.zw, float2(1.0));
    float2 uv = pixel / size;
    float limit = 0.18 + 0.44 * reach;
    if (uv.y > limit + 0.3) return half4(0.0h);

    float2 p = float2(1.8 * uv.x + 0.06 * drift, 2.0 * uv.y + 0.04 * drift);
    float warp = 0.65 * valueNoise(p) + 0.35 * valueNoise(2.1 * p + 5.3);

    float3 rgb = float3(0.0);
    float cover = 0.0;
    for (int k = 2; k >= 0; k--) {
        float d = float(k);
        float speed = 0.46 + 0.19 * d;
        float hang = limit * (0.62 + 0.19 * d)
            + 0.034 * sin((2.7 + 1.25 * d) * uv.x + speed * drift + 2.2 * d)
            + 0.017 * cos((6.1 + 1.6 * d) * uv.x - 0.7 * speed * drift + d)
            + 0.07 * (warp - 0.5);
        float inside = hang - uv.y;
        float feather = 0.12 + 0.05 * d;
        float rise = smoothstep(-1.4 * feather, feather, inside);
        float fill = rise * rise * (0.55 + 0.45 * smoothstep(0.0, max(hang, 0.01), inside));
        float alpha = clamp(fill * (0.42 - 0.1 * d), 0.0, 1.0);
        float3 c = brandRamp(hue + 0.3 * uv.x + 0.07 * d + 0.18 * warp);
        float next = cover + alpha * (1.0 - cover);
        if (next > 1e-5) rgb = mix(rgb, c, alpha / next);
        cover = next;
    }
    bool isDark = dark > 0.5;
    float a = clamp(cover * level * (isDark ? 0.95 : 0.58), 0.0, 1.0);
    if (!isDark) rgb = mix(rgb, float3(1.0), 0.08);
    return half4(half3(rgb * a), half(a));
}
