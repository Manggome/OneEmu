#pragma once
// Vulkan HW-render backend for libretro cores that ask for RETRO_HW_CONTEXT_VULKAN (Dolphin, PPSSPP, Azahar).
//
// The frontend owns VkInstance / VkDevice / the presentation swapchain and hands the core the
// retro_hw_render_interface_vulkan (libretro_vulkan.h). The core renders into its own VkImage, tells us about it
// with set_image() and we sample that image onto the swapchain with a tiny blit pipeline. Device creation goes
// through the core's context negotiation interface (v1/v2) when it provides one, so the core gets the extensions
// and features it needs.
//
// Threading: everything here runs on the emu thread except lock_queue()/unlock_queue(), which the core may call
// from any thread; every queue submission of ours takes queueMutex_ as well.
#include "libretro.h"
#include "libretro_vulkan.h"
#include "video_gl.h" // VideoConfig / AspectMode
#include <android/native_window.h>
#include <mutex>
#include <string>
#include <vector>
#include <vulkan/vulkan.h>

class VideoVK {
public:
    ~VideoVK();

    /** Creates the VkInstance (through the core's create_instance when the v2 negotiation interface offers one) and
     *  picks the physical device. [nego] may be null. Safe to call once; returns false when Vulkan is unusable. */
    bool initInstance(const retro_hw_render_context_negotiation_interface_vulkan* nego);
    /** Creates the surface for [window] and the logical device (core negotiation first, own device as fallback),
     *  then the swapchain. Needs initInstance(). */
    bool initDevice(ANativeWindow* window);
    /** Swaps the presentation window (null = surface lost). Recreates the swapchain; keeps the device. */
    void setWindow(ANativeWindow* window);
    /** true when a swapchain exists and frames can be presented. */
    bool ready() const { return swapchain_ != VK_NULL_HANDLE; }
    bool hasDevice() const { return device_ != VK_NULL_HANDLE; }
    /** Destroys everything (calls the core's destroy_device first when it created the device). */
    void destroy();

    /** Interface handed to the core in RETRO_ENVIRONMENT_GET_HW_RENDER_INTERFACE (valid after initDevice). */
    const retro_hw_render_interface_vulkan* iface() const { return &iface_; }

    /** Acquires the swapchain image for this frame and waits for its previous use: call before retro_run().
     *  Returns false when the swapchain is unusable (the frame should be skipped). */
    bool beginFrame();
    /** Blits the image the core handed us (or the last one, for duped frames) onto the acquired swapchain image and
     *  presents it. [haveFrame] is false when no image was ever set: the screen is cleared to black instead. */
    void present(const VideoConfig& cfg, float coreAspect, bool haveFrame);

    /** Copies the last presented core image to RGBA8 (top-down). */
    bool readback(std::vector<uint32_t>& out, int& w, int& h);

    int width() const { return (int)extent_.width; }
    int height() const { return (int)extent_.height; }
    std::string deviceName() const { return deviceName_; }
    std::string apiVersionString() const;
    /** The last output rectangle (x, y, w, h in surface pixels, top-left origin) — for the pad layout. */
    const int* lastViewport() const { return lastViewport_; }

private:
    // libretro_vulkan.h callbacks (handle = this)
    static void cbSetImage(void*, const retro_vulkan_image*, uint32_t, const VkSemaphore*, uint32_t);
    static uint32_t cbGetSyncIndex(void*);
    static uint32_t cbGetSyncIndexMask(void*);
    static void cbSetCommandBuffers(void*, uint32_t, const VkCommandBuffer*);
    static void cbWaitSyncIndex(void*);
    static void cbLockQueue(void*);
    static void cbUnlockQueue(void*);
    static void cbSetSignalSemaphore(void*, VkSemaphore);
    static VkInstance cbCreateInstanceWrapper(void*, const VkInstanceCreateInfo*);
    static VkDevice cbCreateDeviceWrapper(VkPhysicalDevice, void*, const VkDeviceCreateInfo*);

    bool pickPhysicalDevice();
    bool createOwnDevice();
    bool createSurface(ANativeWindow* window);
    bool createSwapchain();
    void destroySwapchain();
    void waitIdleLocked();
    bool createPipeline();
    void destroyPipeline();
    bool createStaging(uint32_t w, uint32_t h);
    void fillInterface();
    uint32_t findQueueFamily(VkPhysicalDevice gpu, VkSurfaceKHR surface, uint32_t* presentFamily) const;

    // instance / device
    VkInstance instance_ = VK_NULL_HANDLE;
    VkPhysicalDevice gpu_ = VK_NULL_HANDLE;
    VkDevice device_ = VK_NULL_HANDLE;
    VkQueue queue_ = VK_NULL_HANDLE;
    VkQueue presentQueue_ = VK_NULL_HANDLE;
    uint32_t queueFamily_ = 0, presentFamily_ = 0;
    bool coreCreatedDevice_ = false;
    const retro_hw_render_context_negotiation_interface_vulkan* nego_ = nullptr;
    std::string deviceName_;
    uint32_t apiVersion_ = 0;
    std::mutex queueMutex_;

    // surface / swapchain
    ANativeWindow* window_ = nullptr;
    VkSurfaceKHR surface_ = VK_NULL_HANDLE;
    VkSwapchainKHR swapchain_ = VK_NULL_HANDLE;
    VkFormat swapFormat_ = VK_FORMAT_UNDEFINED;
    VkExtent2D extent_{};
    std::vector<VkImage> swapImages_;
    std::vector<VkImageView> swapViews_;
    std::vector<VkFramebuffer> framebuffers_;
    std::vector<VkFence> fences_;              // per frame slot: the submit made in that slot
    std::vector<VkFence> imageFence_;          // per swapchain image: the slot fence of the last submit that rendered to it
    std::vector<VkSemaphore> acquireSems_;     // per frame slot
    std::vector<VkSemaphore> renderDoneSems_;  // per swapchain image
    std::vector<VkDescriptorSet> descSets_;    // per swapchain image
    std::vector<VkCommandBuffer> cmdBufs_;     // per swapchain image
    uint32_t syncIndex_ = 0;                   // acquired swapchain image (== libretro sync index)
    uint32_t frameSlot_ = 0;                   // rotates over acquireSems_
    bool acquired_ = false;
    bool swapchainDirty_ = false;

    // blit pipeline
    VkRenderPass renderPass_ = VK_NULL_HANDLE;
    VkPipelineLayout pipelineLayout_ = VK_NULL_HANDLE;
    VkPipeline pipeline_ = VK_NULL_HANDLE;
    VkDescriptorSetLayout descLayout_ = VK_NULL_HANDLE;
    VkDescriptorPool descPool_ = VK_NULL_HANDLE;
    VkSampler samplerLinear_ = VK_NULL_HANDLE, samplerNearest_ = VK_NULL_HANDLE;
    VkCommandPool cmdPool_ = VK_NULL_HANDLE;

    // the core's frame
    retro_vulkan_image image_{};        // copy of the last set_image (create_info.pNext is not deep-copied)
    bool haveImage_ = false;
    std::vector<VkSemaphore> waitSems_; // from set_image, consumed by the next present
    uint32_t srcQueueFamily_ = VK_QUEUE_FAMILY_IGNORED;
    std::vector<VkCommandBuffer> coreCmds_; // from set_command_buffers, submitted once
    VkSemaphore signalSem_ = VK_NULL_HANDLE; // from set_signal_semaphore, signalled once
    int lastViewport_[4] = {0, 0, 0, 0};

    // readback
    VkBuffer stagingBuf_ = VK_NULL_HANDLE;
    VkDeviceMemory stagingMem_ = VK_NULL_HANDLE;
    uint32_t stagingW_ = 0, stagingH_ = 0;

    retro_hw_render_interface_vulkan iface_{};
};
