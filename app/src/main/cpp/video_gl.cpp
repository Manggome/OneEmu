#include "video_gl.h"
#include "libretro.h"
#include "log.h"
#include <cmath>
#include <cstring>

static const char* kVert = R"(#version 300 es
layout(location = 0) in vec2 aPos;
layout(location = 1) in vec2 aUv;
uniform mat4 uMvp;
uniform int uFlipY;
out vec2 vUv;
void main() {
    vUv = aUv;
    if (uFlipY == 1) vUv.y = 1.0 - vUv.y;
    gl_Position = uMvp * vec4(aPos, 0.0, 1.0);
})";

static const char* kFrag = R"(#version 300 es
precision mediump float;
in vec2 vUv;
uniform sampler2D uTex;
uniform int uSwizzleBGR;
out vec4 fragColor;
void main() {
    vec4 c = texture(uTex, vUv);
    fragColor = (uSwizzleBGR == 1) ? vec4(c.b, c.g, c.r, 1.0) : vec4(c.rgb, 1.0);
})";

static GLuint compile(GLenum type, const char* src) {
    GLuint s = glCreateShader(type);
    glShaderSource(s, 1, &src, nullptr);
    glCompileShader(s);
    GLint ok = 0;
    glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[1024];
        glGetShaderInfoLog(s, sizeof log, nullptr, log);
        LOGE("shader compile failed: %s", log);
    }
    return s;
}

VideoGL::~VideoGL() { destroy(); }

bool VideoGL::init(ANativeWindow* window, bool needDepth, bool needStencil) {
    destroy();
    window_ = window;
    display_ = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (display_ == EGL_NO_DISPLAY || !eglInitialize(display_, nullptr, nullptr)) {
        LOGE("eglInitialize failed");
        return false;
    }
    const EGLint attribs[] = {
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
        EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
        EGL_DEPTH_SIZE, needDepth ? 24 : 0,
        EGL_STENCIL_SIZE, needStencil ? 8 : 0,
        EGL_NONE
    };
    EGLint num = 0;
    if (!eglChooseConfig(display_, attribs, &config_, 1, &num) || num == 0) {
        LOGE("eglChooseConfig failed");
        return false;
    }
    // Prefer an explicit ES 3.2 context (Azahar/PPSSPP use 3.2 features when present), fall back to any ES 3.x.
    const EGLint ctxAttribs32[] = { EGL_CONTEXT_MAJOR_VERSION, 3, EGL_CONTEXT_MINOR_VERSION, 2, EGL_NONE };
    context_ = eglCreateContext(display_, config_, EGL_NO_CONTEXT, ctxAttribs32);
    if (context_ == EGL_NO_CONTEXT) {
        const EGLint ctxAttribs[] = { EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE };
        context_ = eglCreateContext(display_, config_, EGL_NO_CONTEXT, ctxAttribs);
    }
    if (context_ == EGL_NO_CONTEXT) {
        LOGE("eglCreateContext failed: 0x%x", eglGetError());
        return false;
    }
    if (!createSurface()) return false;
    if (!makeCurrent()) return false;
    ensureProgram();
    LOGI("GL ready: %s / %s", glGetString(GL_RENDERER), glGetString(GL_VERSION));
    return true;
}

bool VideoGL::createSurface() {
    if (surface_ != EGL_NO_SURFACE) {
        eglMakeCurrent(display_, EGL_NO_SURFACE, EGL_NO_SURFACE, context_);
        eglDestroySurface(display_, surface_);
        surface_ = EGL_NO_SURFACE;
    }
    if (!window_) return false;
    surface_ = eglCreateWindowSurface(display_, config_, window_, nullptr);
    if (surface_ == EGL_NO_SURFACE) {
        LOGE("eglCreateWindowSurface failed: 0x%x", eglGetError());
        return false;
    }
    eglQuerySurface(display_, surface_, EGL_WIDTH, &surfaceW_);
    eglQuerySurface(display_, surface_, EGL_HEIGHT, &surfaceH_);
    return true;
}

void VideoGL::setWindow(ANativeWindow* window) {
    window_ = window;
    if (context_ == EGL_NO_CONTEXT) return;
    if (window) {
        createSurface();
        makeCurrent();
    } else if (surface_ != EGL_NO_SURFACE) {
        eglMakeCurrent(display_, EGL_NO_SURFACE, EGL_NO_SURFACE, context_);
        eglDestroySurface(display_, surface_);
        surface_ = EGL_NO_SURFACE;
    }
}

bool VideoGL::makeCurrent() {
    if (display_ == EGL_NO_DISPLAY || context_ == EGL_NO_CONTEXT) return false;
    if (!eglMakeCurrent(display_, surface_, surface_, context_)) {
        LOGE("eglMakeCurrent failed: 0x%x", eglGetError());
        return false;
    }
    if (surface_ != EGL_NO_SURFACE) {
        eglQuerySurface(display_, surface_, EGL_WIDTH, &surfaceW_);
        eglQuerySurface(display_, surface_, EGL_HEIGHT, &surfaceH_);
    }
    return true;
}

void VideoGL::setSwapInterval(int interval) {
    if (display_ != EGL_NO_DISPLAY && surface_ != EGL_NO_SURFACE) eglSwapInterval(display_, interval);
}

void VideoGL::ensureProgram() {
    if (program_) return;
    GLuint v = compile(GL_VERTEX_SHADER, kVert);
    GLuint f = compile(GL_FRAGMENT_SHADER, kFrag);
    program_ = glCreateProgram();
    glAttachShader(program_, v);
    glAttachShader(program_, f);
    glLinkProgram(program_);
    glDeleteShader(v);
    glDeleteShader(f);
    uTex_ = glGetUniformLocation(program_, "uTex");
    uSwizzleBGR_ = glGetUniformLocation(program_, "uSwizzleBGR");
    uMvp_ = glGetUniformLocation(program_, "uMvp");
    uFlipY_ = glGetUniformLocation(program_, "uFlipY");

    // pos.xy, uv.xy — a unit quad; texture v=0 is the top row (top-down images).
    const float quad[] = {
        -1.f, -1.f, 0.f, 1.f,
         1.f, -1.f, 1.f, 1.f,
        -1.f,  1.f, 0.f, 0.f,
         1.f,  1.f, 1.f, 0.f,
    };
    glGenVertexArrays(1, &vao_);
    glBindVertexArray(vao_);
    glGenBuffers(1, &vbo_);
    glBindBuffer(GL_ARRAY_BUFFER, vbo_);
    glBufferData(GL_ARRAY_BUFFER, sizeof quad, quad, GL_STATIC_DRAW);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 16, (void*)0);
    glEnableVertexAttribArray(1);
    glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 16, (void*)8);
    glBindVertexArray(0);

    glGenTextures(1, &swTex_);
    glBindTexture(GL_TEXTURE_2D, swTex_);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
}

void VideoGL::uploadSoftwareFrame(const void* data, unsigned w, unsigned h, size_t pitch, int format) {
    if (!ready()) return;
    lastWasHw_ = false;
    if (!data) return; // dupe frame: keep the texture as is
    ensureProgram();
    glBindTexture(GL_TEXTURE_2D, swTex_);

    GLenum internal = GL_RGB565, fmt = GL_RGB, type = GL_UNSIGNED_SHORT_5_6_5;
    unsigned bpp = 2;
    const void* src = data;
    if (format == RETRO_PIXEL_FORMAT_XRGB8888) {
        internal = GL_RGBA8; fmt = GL_RGBA; type = GL_UNSIGNED_BYTE; bpp = 4;
    } else if (format == RETRO_PIXEL_FORMAT_0RGB1555) {
        // Convert to RGB565 on the CPU; very few cores use this format.
        convBuf_.resize((size_t)w * h);
        for (unsigned y = 0; y < h; y++) {
            const uint16_t* row = (const uint16_t*)((const uint8_t*)data + y * pitch);
            for (unsigned x = 0; x < w; x++) {
                uint16_t p = row[x];
                uint16_t r = (p >> 10) & 0x1f, g = (p >> 5) & 0x1f, b = p & 0x1f;
                convBuf_[(size_t)y * w + x] = (uint16_t)((r << 11) | (((g << 1) | (g >> 4)) << 5) | b);
            }
        }
        src = convBuf_.data();
        pitch = w * 2;
    }
    if (swTexW_ != w || swTexH_ != h || swFormat_ != format) {
        glTexImage2D(GL_TEXTURE_2D, 0, internal, w, h, 0, fmt, type, nullptr);
        swTexW_ = w; swTexH_ = h; swFormat_ = format;
    }
    glPixelStorei(GL_UNPACK_ROW_LENGTH, (GLint)(pitch / bpp));
    glPixelStorei(GL_UNPACK_ALIGNMENT, bpp == 2 ? 2 : 4);
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, w, h, fmt, type, src);
    glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
    frameW_ = w; frameH_ = h;
    haveFrame_ = true;
}

bool VideoGL::createHwFramebuffer(unsigned maxW, unsigned maxH, bool depth, bool stencil) {
    destroyHwFramebuffer();
    if (maxW == 0 || maxH == 0) { maxW = 1920; maxH = 1080; }
    hwW_ = maxW; hwH_ = maxH;
    glGenTextures(1, &hwTex_);
    glBindTexture(GL_TEXTURE_2D, hwTex_);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, maxW, maxH, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glGenFramebuffers(1, &hwFbo_);
    glBindFramebuffer(GL_FRAMEBUFFER, hwFbo_);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, hwTex_, 0);
    if (depth || stencil) {
        glGenRenderbuffers(1, &hwDepth_);
        glBindRenderbuffer(GL_RENDERBUFFER, hwDepth_);
        glRenderbufferStorage(GL_RENDERBUFFER, stencil ? GL_DEPTH24_STENCIL8 : GL_DEPTH_COMPONENT24, maxW, maxH);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, stencil ? GL_DEPTH_STENCIL_ATTACHMENT : GL_DEPTH_ATTACHMENT,
                                  GL_RENDERBUFFER, hwDepth_);
    }
    GLenum status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    if (status != GL_FRAMEBUFFER_COMPLETE) {
        LOGE("HW framebuffer incomplete: 0x%x", status);
        destroyHwFramebuffer();
        return false;
    }
    LOGI("HW framebuffer %ux%u created (depth=%d stencil=%d)", maxW, maxH, depth, stencil);
    return true;
}

void VideoGL::destroyHwFramebuffer() {
    if (hwFbo_) glDeleteFramebuffers(1, &hwFbo_);
    if (hwTex_) glDeleteTextures(1, &hwTex_);
    if (hwDepth_) glDeleteRenderbuffers(1, &hwDepth_);
    hwFbo_ = hwTex_ = hwDepth_ = 0;
}

void VideoGL::bindDefaultFramebuffer() { glBindFramebuffer(GL_FRAMEBUFFER, 0); }

void VideoGL::present(const VideoConfig& cfg, float coreAspect, bool hwFrame) {
    if (!ready()) return;
    ensureProgram();
    if (hwFrame) { lastWasHw_ = true; haveFrame_ = true; }
    unsigned fw = frameW_, fh = frameH_;
    if (fw == 0 || fh == 0) return;

    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glViewport(0, 0, surfaceW_, surfaceH_);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_SCISSOR_TEST);
    glDisable(GL_BLEND);
    glDisable(GL_CULL_FACE);
    glClearColor(0, 0, 0, 1);
    glClear(GL_COLOR_BUFFER_BIT);

    // Output rectangle.
    float aspect = coreAspect > 0 ? coreAspect : (float)fw / (float)fh;
    if (cfg.aspect == AspectMode::Square) aspect = (float)fw / (float)fh;
    bool rotated = (cfg.rotation % 2) == 1;
    if (rotated) aspect = 1.0f / aspect;
    float outW = (float)surfaceW_, outH = (float)surfaceH_;
    if (cfg.aspect != AspectMode::Stretch) {
        if (outW / outH > aspect) outW = outH * aspect; else outH = outW / aspect;
        if (cfg.aspect == AspectMode::Integer) {
            float baseH = rotated ? (float)fw : (float)fh;
            int scale = (int)std::floor(outH / baseH);
            if (scale >= 1) { outH = baseH * scale; outW = outH * aspect; }
        }
    }
    float sx = outW / (float)surfaceW_, sy = outH / (float)surfaceH_;
    lastViewport_[0] = (int)((surfaceW_ - outW) / 2); lastViewport_[1] = (int)((surfaceH_ - outH) / 2);
    lastViewport_[2] = (int)outW; lastViewport_[3] = (int)outH;

    float c = std::cos(-(float)cfg.rotation * (float)M_PI_2), s = std::sin(-(float)cfg.rotation * (float)M_PI_2);
    // column-major: scale then rotate
    float mvp[16] = {
        c * sx,  s * sy, 0, 0,
       -s * sx,  c * sy, 0, 0,
        0,       0,      1, 0,
        0,       0,      0, 1,
    };

    glUseProgram(program_);
    glBindVertexArray(vao_);
    glActiveTexture(GL_TEXTURE0);
    if (hwFrame || (lastWasHw_ && hwTex_)) {
        glBindTexture(GL_TEXTURE_2D, hwTex_);
        // The core only used the fw×fh sub-rectangle of the hwW×hwH texture.
        float u = (float)fw / (float)hwW_, v = (float)fh / (float)hwH_;
        const float quad[] = {
            -1.f, -1.f, 0.f, v,
             1.f, -1.f, u,   v,
            -1.f,  1.f, 0.f, 0.f,
             1.f,  1.f, u,   0.f,
        };
        glBindBuffer(GL_ARRAY_BUFFER, vbo_);
        glBufferData(GL_ARRAY_BUFFER, sizeof quad, quad, GL_DYNAMIC_DRAW);
        glUniform1i(uFlipY_, cfg.bottomLeftOrigin ? 1 : 0);
        glUniform1i(uSwizzleBGR_, 0);
    } else {
        glBindTexture(GL_TEXTURE_2D, swTex_);
        const float quad[] = {
            -1.f, -1.f, 0.f, 1.f,
             1.f, -1.f, 1.f, 1.f,
            -1.f,  1.f, 0.f, 0.f,
             1.f,  1.f, 1.f, 0.f,
        };
        glBindBuffer(GL_ARRAY_BUFFER, vbo_);
        glBufferData(GL_ARRAY_BUFFER, sizeof quad, quad, GL_DYNAMIC_DRAW);
        glUniform1i(uFlipY_, 0);
        glUniform1i(uSwizzleBGR_, swFormat_ == RETRO_PIXEL_FORMAT_XRGB8888 ? 1 : 0);
    }
    GLint filter = cfg.linearFilter ? GL_LINEAR : GL_NEAREST;
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filter);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filter);
    glUniform1i(uTex_, 0);
    glUniformMatrix4fv(uMvp_, 1, GL_FALSE, mvp);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glBindVertexArray(0);
}

void VideoGL::swap() {
    if (ready()) eglSwapBuffers(display_, surface_);
}

bool VideoGL::readback(std::vector<uint32_t>& out, int& w, int& h) {
    if (!ready() || !haveFrame_) return false;
    w = lastViewport_[2]; h = lastViewport_[3];
    if (w <= 0 || h <= 0) return false;
    out.resize((size_t)w * h);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glPixelStorei(GL_PACK_ALIGNMENT, 4);
    glReadPixels(lastViewport_[0], lastViewport_[1], w, h, GL_RGBA, GL_UNSIGNED_BYTE, out.data());
    // GL reads bottom-up; flip to top-down.
    std::vector<uint32_t> row((size_t)w);
    for (int y = 0; y < h / 2; y++) {
        uint32_t* a = out.data() + (size_t)y * w;
        uint32_t* b = out.data() + (size_t)(h - 1 - y) * w;
        memcpy(row.data(), a, w * 4); memcpy(a, b, w * 4); memcpy(b, row.data(), w * 4);
    }
    return true;
}

void VideoGL::destroy() {
    if (display_ != EGL_NO_DISPLAY) {
        eglMakeCurrent(display_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (context_ != EGL_NO_CONTEXT) {
            // GL objects die with the context.
            eglDestroyContext(display_, context_);
        }
        if (surface_ != EGL_NO_SURFACE) eglDestroySurface(display_, surface_);
        eglTerminate(display_);
    }
    display_ = EGL_NO_DISPLAY; context_ = EGL_NO_CONTEXT; surface_ = EGL_NO_SURFACE;
    program_ = vbo_ = vao_ = swTex_ = 0; hwFbo_ = hwTex_ = hwDepth_ = 0;
    swTexW_ = swTexH_ = 0; swFormat_ = -1; haveFrame_ = false;
}
