#version 450
// Screen filters; the same effects live in kFrag in video_gl.cpp, keep the two in step. Everything
// works in source pixels through textureSize, which is right both for a software frame and for the
// visible part of a hardware core's larger image, since vUv spans exactly that part.
layout(set = 0, binding = 0) uniform sampler2D uTex;
layout(push_constant) uniform PC { vec4 uvRect; vec2 rot; vec2 pad; vec4 filterParams; } pc;
layout(location = 0) in vec2 vUv;
layout(location = 0) out vec4 fragColor;

const float PI = 3.14159265;

void main() {
    vec3 c = texture(uTex, vUv).rgb;
    int mode = int(pc.filterParams.x);
    float k = pc.filterParams.y;
    if (mode != 0 && k > 0.0) {
        vec2 src = vec2(textureSize(uTex, 0));
        if (mode == 1 || mode == 2) {
            float s = sin(vUv.y * src.y * PI);
            c *= mix(1.0, s * s, k);
            if (mode == 2) {
                int col = int(mod(gl_FragCoord.x, 3.0));
                vec3 m = col == 0 ? vec3(1.0, 0.72, 0.72) : (col == 1 ? vec3(0.72, 1.0, 0.72) : vec3(0.72, 0.72, 1.0));
                c *= mix(vec3(1.0), m, k);
            }
            c *= 1.0 + 0.45 * k;
        } else {
            vec2 p = fract(vUv * src);
            vec2 g = smoothstep(vec2(0.0), vec2(0.18), p) * smoothstep(vec2(0.0), vec2(0.18), 1.0 - p);
            c *= mix(1.0, g.x * g.y, k);
            c *= 1.0 + 0.35 * k;
        }
    }
    fragColor = vec4(c, 1.0);
}
