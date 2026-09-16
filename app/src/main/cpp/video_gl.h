#pragma once
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <android/native_window.h>
#include <cstdint>
#include <string>
#include <vector>

enum class AspectMode : int { Core = 0, Stretch = 1, Integer = 2, Square = 3 };

struct VideoConfig {
    bool linearFilter = false;
    AspectMode aspect = AspectMode::Core;
    unsigned rotation = 0;        // 0..3, multiples of 90° clockwise, from RETRO_ENVIRONMENT_SET_ROTATION
    bool bottomLeftOrigin = false; // HW render cores draw with GL's origin
    // Viewport the game image is letterboxed into, normalized 0..1 of the surface with a top-left origin
    // (x,y = top-left corner). 0,0,1,1 = the whole surface (legacy behaviour).
    float vpX = 0.f, vpY = 0.f, vpW = 1.f, vpH = 1.f;
};

// Owns the EGL display/context/surface and presents either a software framebuffer
// (uploaded as a texture) or a hardware-render FBO that the core drew into.
class VideoGL {
public:
    ~VideoGL();

    // reqMajor/reqMinor: GLES version a HW-render core asked for (0 = any ES 3.x). The exact version is
    // tried first, then ES 3.2 and finally any ES 3.x; check glMajor()/glMinor() for what was granted.
    bool init(ANativeWindow* window, bool needDepth, bool needStencil, int reqMajor = 0, int reqMinor = 0);
    void setWindow(ANativeWindow* window); // swap the EGLSurface without recreating the context
    void destroy();
    bool ready() const { return context_ != EGL_NO_CONTEXT && surface_ != EGL_NO_SURFACE; }
    bool makeCurrent();
    /** Cores that ask for RETRO_ENVIRONMENT_SET_HW_SHARED_CONTEXT get their own context; our presentation
     *  runs in a second, shared context so their cached GL state is never clobbered. */
    void setSharedContext(bool on) { sharedContext_ = on; }
    bool makeCurrentPresent();
    /** Call with the core context current after retro_run: records a fence the present pass waits on. */
    void fenceCoreFrame();
    /** Call with the core context current before retro_run: the GPU waits until the previous present
     *  (issued from the present context) has finished reading the HW texture before the core overwrites it. */
    void waitPresentFence();
    /** Diagnostics: drains glGetError() and logs each distinct error once per ~5 s with [where]. */
    void logGlErrors(const char* where);

    void setSwapInterval(int interval);
    int width() const { return surfaceW_; }
    int height() const { return surfaceH_; }
    // Actual context version parsed from GL_VERSION ("OpenGL ES 3.1 ..."), valid after init().
    int glMajor() const { return glMajor_; }
    int glMinor() const { return glMinor_; }
    const std::string& versionString() const { return glVersion_; }
    const std::string& rendererString() const { return glRenderer_; }

    // Software path: format is one of RETRO_PIXEL_FORMAT_*. data may be nullptr to re-present last frame.
    void uploadSoftwareFrame(const void* data, unsigned w, unsigned h, size_t pitch, int format);

    // Hardware render path.
    bool createHwFramebuffer(unsigned maxW, unsigned maxH, bool depth, bool stencil);
    GLuint hwFramebuffer() const { return hwFbo_; }
    /** Diagnostics: RGBA of the centre pixel of the core's HW frame (core context must be current). */
    uint32_t sampleHwFrameCentre();
    void setHwFrameSize(unsigned w, unsigned h) { frameW_ = w; frameH_ = h; }
    void destroyHwFramebuffer();

    void present(const VideoConfig& cfg, float coreAspect, bool hwFrame);
    void swap();
    // Reads back the last presented frame (as RGBA8888, top-down). Returns false if not ready.
    bool readback(std::vector<uint32_t>& out, int& w, int& h);

    void bindDefaultFramebuffer();

private:
    bool createSurface();
    void ensureProgram();

    EGLDisplay display_ = EGL_NO_DISPLAY;
    EGLConfig config_ = nullptr;
    EGLContext context_ = EGL_NO_CONTEXT;      // the core's context (retro_run, context_reset, HW FBO)
    EGLContext presentCtx_ = EGL_NO_CONTEXT;   // our blit context when sharedContext_ is on
    bool sharedContext_ = false;
    int ctxReqMajor_ = 0, ctxReqMinor_ = 0;
    bool ensurePresentContext();
    EGLContext createContext(EGLContext share);
    EGLSurface surface_ = EGL_NO_SURFACE;
    ANativeWindow* window_ = nullptr;
    int surfaceW_ = 0, surfaceH_ = 0;
    int glMajor_ = 0, glMinor_ = 0;
    std::string glVersion_, glRenderer_;

    GLuint program_ = 0, vbo_ = 0, vao_ = 0;
    GLint uTex_ = -1, uSwizzleBGR_ = -1, uMvp_ = -1, uFlipY_ = -1;
    GLuint swTex_ = 0;
    unsigned swTexW_ = 0, swTexH_ = 0;
    int swFormat_ = -1;
    std::vector<uint16_t> convBuf_;

    GLuint hwFbo_ = 0, hwTex_ = 0, hwDepth_ = 0;
    GLsync presentFence_ = nullptr; // set by swap() in the present context, consumed by waitPresentFence()
    unsigned hwW_ = 0, hwH_ = 0;
    unsigned frameW_ = 0, frameH_ = 0;
    bool lastWasHw_ = false;
    bool haveFrame_ = false;
    int lastViewport_[4] = {0, 0, 0, 0};
};
