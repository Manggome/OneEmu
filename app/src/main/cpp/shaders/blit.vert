#version 450
// Fullscreen quad; the letterbox rectangle is applied through the viewport, rotation via push constant.
layout(push_constant) uniform PC { vec4 uvRect; vec2 rot; vec2 pad; } pc; // uvRect = u0,v0,u1,v1 ; rot = cos,sin
layout(location = 0) out vec2 vUv;
void main() {
    vec2 pos = vec2((gl_VertexIndex & 1) == 1 ? 1.0 : -1.0, (gl_VertexIndex & 2) == 2 ? 1.0 : -1.0);
    vec2 uv01 = vec2((gl_VertexIndex & 1) == 1 ? 1.0 : 0.0, (gl_VertexIndex & 2) == 2 ? 1.0 : 0.0);
    vUv = mix(pc.uvRect.xy, pc.uvRect.zw, uv01);
    vec2 r = vec2(pc.rot.x * pos.x - pc.rot.y * pos.y, pc.rot.y * pos.x + pc.rot.x * pos.y);
    gl_Position = vec4(r, 0.0, 1.0);
}
