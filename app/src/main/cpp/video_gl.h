#pragma once
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <android/native_window.h>
#include <cstdint>
#include <vector>

enum class AspectMode : int { Core = 0, Stretch = 1, Integer = 2, Square = 3 };

struct VideoConfig {
    bool linearFilter = false;
    AspectMode aspect = AspectMode::Core;
    unsigned rotation = 0;        // 0..3, multiples of 90° clockwise, from RETRO_ENVIRONMENT_SET_ROTATION
    bool bottomLeftOrigin = false; // HW render cores draw with GL's origin
};

// Owns the EGL display/context/surface and presents either a software framebuffer
// (uploaded as a texture) or a hardware-render FBO that the core drew into.
class VideoGL {
public:
    ~VideoGL();

    bool init(ANativeWindow* window, bool needDepth, bool needStencil);
    void setWindow(ANativeWindow* window); // swap the EGLSurface without recreating the context
    void destroy();
    bool ready() const { return context_ != EGL_NO_CONTEXT && surface_ != EGL_NO_SURFACE; }
    bool makeCurrent();

    void setSwapInterval(int interval);
    int width() const { return surfaceW_; }
    int height() const { return surfaceH_; }

    // Software path: format is one of RETRO_PIXEL_FORMAT_*. data may be nullptr to re-present last frame.
    void uploadSoftwareFrame(const void* data, unsigned w, unsigned h, size_t pitch, int format);

    // Hardware render path.
    bool createHwFramebuffer(unsigned maxW, unsigned maxH, bool depth, bool stencil);
    GLuint hwFramebuffer() const { return hwFbo_; }
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
    EGLContext context_ = EGL_NO_CONTEXT;
    EGLSurface surface_ = EGL_NO_SURFACE;
    ANativeWindow* window_ = nullptr;
    int surfaceW_ = 0, surfaceH_ = 0;

    GLuint program_ = 0, vbo_ = 0, vao_ = 0;
    GLint uTex_ = -1, uSwizzleBGR_ = -1, uMvp_ = -1, uFlipY_ = -1;
    GLuint swTex_ = 0;
    unsigned swTexW_ = 0, swTexH_ = 0;
    int swFormat_ = -1;
    std::vector<uint16_t> convBuf_;

    GLuint hwFbo_ = 0, hwTex_ = 0, hwDepth_ = 0;
    unsigned hwW_ = 0, hwH_ = 0;
    unsigned frameW_ = 0, frameH_ = 0;
    bool lastWasHw_ = false;
    bool haveFrame_ = false;
    int lastViewport_[4] = {0, 0, 0, 0};
};
