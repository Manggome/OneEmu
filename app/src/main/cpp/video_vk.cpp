#include "video_vk.h"
#include "log.h"
#include "vk_shaders.h"
#include <algorithm>
#include <cmath>
#include <cstring>
#include <vulkan/vulkan_android.h>

// ---------------------------------------------------------------- helpers
namespace {
const char* vkResultName(VkResult r) {
    switch (r) {
        case VK_SUCCESS: return "VK_SUCCESS";
        case VK_NOT_READY: return "VK_NOT_READY";
        case VK_TIMEOUT: return "VK_TIMEOUT";
        case VK_SUBOPTIMAL_KHR: return "VK_SUBOPTIMAL_KHR";
        case VK_ERROR_OUT_OF_DATE_KHR: return "VK_ERROR_OUT_OF_DATE_KHR";
        case VK_ERROR_SURFACE_LOST_KHR: return "VK_ERROR_SURFACE_LOST_KHR";
        case VK_ERROR_DEVICE_LOST: return "VK_ERROR_DEVICE_LOST";
        case VK_ERROR_OUT_OF_HOST_MEMORY: return "VK_ERROR_OUT_OF_HOST_MEMORY";
        case VK_ERROR_OUT_OF_DEVICE_MEMORY: return "VK_ERROR_OUT_OF_DEVICE_MEMORY";
        case VK_ERROR_INITIALIZATION_FAILED: return "VK_ERROR_INITIALIZATION_FAILED";
        case VK_ERROR_EXTENSION_NOT_PRESENT: return "VK_ERROR_EXTENSION_NOT_PRESENT";
        case VK_ERROR_FEATURE_NOT_PRESENT: return "VK_ERROR_FEATURE_NOT_PRESENT";
        case VK_ERROR_INCOMPATIBLE_DRIVER: return "VK_ERROR_INCOMPATIBLE_DRIVER";
        case VK_ERROR_NATIVE_WINDOW_IN_USE_KHR: return "VK_ERROR_NATIVE_WINDOW_IN_USE_KHR";
        default: return "VkResult(?)";
    }
}
#define VK_CHECK(expr, what) do { VkResult r__ = (expr); if (r__ != VK_SUCCESS) { LOGE("vulkan: %s failed: %s (%d)", what, vkResultName(r__), (int)r__); return false; } } while (0)

const char* kInstanceExts[] = { VK_KHR_SURFACE_EXTENSION_NAME, VK_KHR_ANDROID_SURFACE_EXTENSION_NAME };
const char* kDeviceExts[] = { VK_KHR_SWAPCHAIN_EXTENSION_NAME };

bool hasExtension(const std::vector<VkExtensionProperties>& props, const char* name) {
    for (auto& p : props) if (!strcmp(p.extensionName, name)) return true;
    return false;
}
} // namespace

VideoVK::~VideoVK() { destroy(); }

std::string VideoVK::apiVersionString() const {
    char buf[64];
    snprintf(buf, sizeof buf, "Vulkan %u.%u.%u", VK_VERSION_MAJOR(apiVersion_), VK_VERSION_MINOR(apiVersion_), VK_VERSION_PATCH(apiVersion_));
    return buf;
}

// ---------------------------------------------------------------- instance
VkInstance VideoVK::cbCreateInstanceWrapper(void* opaque, const VkInstanceCreateInfo* coreInfo) {
    // v2 negotiation: the core built its create info; add the extensions we need for presentation.
    auto* self = (VideoVK*)opaque;
    std::vector<const char*> exts(coreInfo->ppEnabledExtensionNames, coreInfo->ppEnabledExtensionNames + coreInfo->enabledExtensionCount);
    for (const char* e : kInstanceExts) {
        bool have = false;
        for (auto x : exts) if (!strcmp(x, e)) have = true;
        if (!have) exts.push_back(e);
    }
    VkInstanceCreateInfo info = *coreInfo;
    info.enabledExtensionCount = (uint32_t)exts.size();
    info.ppEnabledExtensionNames = exts.data();
    VkInstance inst = VK_NULL_HANDLE;
    VkResult r = vkCreateInstance(&info, nullptr, &inst);
    if (r != VK_SUCCESS) { LOGE("vulkan: vkCreateInstance (core wrapper) failed: %s", vkResultName(r)); return VK_NULL_HANDLE; }
    if (info.pApplicationInfo) self->apiVersion_ = info.pApplicationInfo->apiVersion;
    return inst;
}

bool VideoVK::initInstance(const retro_hw_render_context_negotiation_interface_vulkan* nego) {
    if (instance_ != VK_NULL_HANDLE) return true;
    nego_ = nego;

    uint32_t loaderVersion = VK_API_VERSION_1_0;
    // Not in the API-26 libvulkan stub we link against (added in API 29): resolve it through the loader.
    auto enumVersion = (PFN_vkEnumerateInstanceVersion)vkGetInstanceProcAddr(nullptr, "vkEnumerateInstanceVersion");
    if (enumVersion) enumVersion(&loaderVersion);

    VkApplicationInfo defaultApp{ VK_STRUCTURE_TYPE_APPLICATION_INFO };
    defaultApp.pApplicationName = "OneEmu";
    defaultApp.applicationVersion = 1;
    defaultApp.pEngineName = "OneEmu libretro";
    defaultApp.engineVersion = 1;
    defaultApp.apiVersion = VK_API_VERSION_1_1;
    const VkApplicationInfo* app = nullptr;
    if (nego && nego->get_application_info) app = nego->get_application_info();
    VkApplicationInfo appCopy = app ? *app : defaultApp;
    // Android loaders lag; never ask for more than the loader reports, and at least 1.1 (the libretro
    // recommendation) when the core did not say.
    if (appCopy.apiVersion < VK_API_VERSION_1_1) appCopy.apiVersion = VK_API_VERSION_1_1; // Dolphin/PPSSPP say 1.0; the header allows bumping
    if (appCopy.apiVersion > loaderVersion) appCopy.apiVersion = loaderVersion;
    apiVersion_ = appCopy.apiVersion;

    if (nego && nego->interface_version >= 2 && nego->create_instance) {
        instance_ = nego->create_instance(vkGetInstanceProcAddr, &appCopy, &VideoVK::cbCreateInstanceWrapper, this);
        if (instance_ == VK_NULL_HANDLE) LOGW("vulkan: core create_instance returned null, creating our own");
    }
    if (instance_ == VK_NULL_HANDLE) {
        std::vector<const char*> exts(kInstanceExts, kInstanceExts + sizeof kInstanceExts / sizeof kInstanceExts[0]);
        uint32_t ne = 0;
        vkEnumerateInstanceExtensionProperties(nullptr, &ne, nullptr);
        std::vector<VkExtensionProperties> avail(ne);
        vkEnumerateInstanceExtensionProperties(nullptr, &ne, avail.data());
        if (hasExtension(avail, "VK_KHR_get_physical_device_properties2")) exts.push_back("VK_KHR_get_physical_device_properties2");
        VkInstanceCreateInfo info{ VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO };
        info.pApplicationInfo = &appCopy;
        info.enabledExtensionCount = (uint32_t)exts.size();
        info.ppEnabledExtensionNames = exts.data();
        VK_CHECK(vkCreateInstance(&info, nullptr, &instance_), "vkCreateInstance");
    }
    if (!pickPhysicalDevice()) return false;
    LOGI("vulkan: instance ready (%s, loader %u.%u), gpu %s", apiVersionString().c_str(),
         VK_VERSION_MAJOR(loaderVersion), VK_VERSION_MINOR(loaderVersion), deviceName_.c_str());
    return true;
}

bool VideoVK::pickPhysicalDevice() {
    uint32_t n = 0;
    VK_CHECK(vkEnumeratePhysicalDevices(instance_, &n, nullptr), "vkEnumeratePhysicalDevices");
    if (n == 0) { LOGE("vulkan: no physical devices"); return false; }
    std::vector<VkPhysicalDevice> gpus(n);
    vkEnumeratePhysicalDevices(instance_, &n, gpus.data());
    // Android has one GPU; prefer a device with a graphics+compute queue anyway.
    for (auto g : gpus) {
        if (findQueueFamily(g, VK_NULL_HANDLE, nullptr) != UINT32_MAX) { gpu_ = g; break; }
    }
    if (gpu_ == VK_NULL_HANDLE) gpu_ = gpus[0];
    VkPhysicalDeviceProperties props{};
    vkGetPhysicalDeviceProperties(gpu_, &props);
    deviceName_ = props.deviceName;
    char buf[160];
    snprintf(buf, sizeof buf, "%s (driver %u.%u.%u, api %u.%u)", props.deviceName, VK_VERSION_MAJOR(props.driverVersion),
             VK_VERSION_MINOR(props.driverVersion), VK_VERSION_PATCH(props.driverVersion), VK_VERSION_MAJOR(props.apiVersion),
             VK_VERSION_MINOR(props.apiVersion));
    deviceName_ = buf;
    return true;
}

uint32_t VideoVK::findQueueFamily(VkPhysicalDevice gpu, VkSurfaceKHR surface, uint32_t* presentFamily) const {
    uint32_t n = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(gpu, &n, nullptr);
    std::vector<VkQueueFamilyProperties> fams(n);
    vkGetPhysicalDeviceQueueFamilyProperties(gpu, &n, fams.data());
    uint32_t best = UINT32_MAX, present = UINT32_MAX;
    for (uint32_t i = 0; i < n; i++) {
        bool gfx = (fams[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) && (fams[i].queueFlags & VK_QUEUE_COMPUTE_BIT);
        VkBool32 canPresent = VK_FALSE;
        if (surface != VK_NULL_HANDLE) vkGetPhysicalDeviceSurfaceSupportKHR(gpu, i, surface, &canPresent);
        if (gfx && best == UINT32_MAX) best = i;
        if (gfx && canPresent) { best = i; present = i; break; }
        if (canPresent && present == UINT32_MAX) present = i;
    }
    if (presentFamily) *presentFamily = present;
    return best;
}

// ---------------------------------------------------------------- device
VkDevice VideoVK::cbCreateDeviceWrapper(VkPhysicalDevice gpu, void* opaque, const VkDeviceCreateInfo* coreInfo) {
    (void)opaque;
    std::vector<const char*> exts(coreInfo->ppEnabledExtensionNames, coreInfo->ppEnabledExtensionNames + coreInfo->enabledExtensionCount);
    for (const char* e : kDeviceExts) {
        bool have = false;
        for (auto x : exts) if (!strcmp(x, e)) have = true;
        if (!have) exts.push_back(e);
    }
    VkDeviceCreateInfo info = *coreInfo;
    info.enabledExtensionCount = (uint32_t)exts.size();
    info.ppEnabledExtensionNames = exts.data();
    VkDevice dev = VK_NULL_HANDLE;
    VkResult r = vkCreateDevice(gpu, &info, nullptr, &dev);
    if (r != VK_SUCCESS) { LOGE("vulkan: vkCreateDevice (core wrapper) failed: %s", vkResultName(r)); return VK_NULL_HANDLE; }
    return dev;
}

bool VideoVK::createOwnDevice() {
    uint32_t present = UINT32_MAX;
    queueFamily_ = findQueueFamily(gpu_, surface_, &present);
    if (queueFamily_ == UINT32_MAX) { LOGE("vulkan: no graphics+compute queue family"); return false; }
    presentFamily_ = present == UINT32_MAX ? queueFamily_ : present;
    float prio = 1.0f;
    VkDeviceQueueCreateInfo q[2]{};
    uint32_t nq = 0;
    q[nq] = { VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO }; q[nq].queueFamilyIndex = queueFamily_; q[nq].queueCount = 1; q[nq].pQueuePriorities = &prio; nq++;
    if (presentFamily_ != queueFamily_) { q[nq] = q[0]; q[nq].queueFamilyIndex = presentFamily_; nq++; }
    VkPhysicalDeviceFeatures feats{};
    VkDeviceCreateInfo info{ VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO };
    info.queueCreateInfoCount = nq;
    info.pQueueCreateInfos = q;
    info.enabledExtensionCount = (uint32_t)(sizeof kDeviceExts / sizeof kDeviceExts[0]);
    info.ppEnabledExtensionNames = kDeviceExts;
    info.pEnabledFeatures = &feats;
    VK_CHECK(vkCreateDevice(gpu_, &info, nullptr, &device_), "vkCreateDevice");
    vkGetDeviceQueue(device_, queueFamily_, 0, &queue_);
    vkGetDeviceQueue(device_, presentFamily_, 0, &presentQueue_);
    coreCreatedDevice_ = false;
    return true;
}

bool VideoVK::initDevice(ANativeWindow* window) {
    if (instance_ == VK_NULL_HANDLE) return false;
    if (!createSurface(window)) return false;
    if (device_ == VK_NULL_HANDLE) {
        retro_vulkan_context ctx{};
        bool ok = false;
        if (nego_) {
            if (nego_->interface_version >= 2 && nego_->create_device2) {
                ok = nego_->create_device2(&ctx, instance_, gpu_, surface_, vkGetInstanceProcAddr, &VideoVK::cbCreateDeviceWrapper, this);
                if (!ok) {
                    LOGW("vulkan: core create_device2 rejected the gpu, retrying with gpu = null");
                    ok = nego_->create_device2(&ctx, instance_, VK_NULL_HANDLE, surface_, vkGetInstanceProcAddr, &VideoVK::cbCreateDeviceWrapper, this);
                }
            } else if (nego_->create_device) {
                VkPhysicalDeviceFeatures feats{};
                ok = nego_->create_device(&ctx, instance_, gpu_, surface_, vkGetInstanceProcAddr, kDeviceExts,
                                          (unsigned)(sizeof kDeviceExts / sizeof kDeviceExts[0]), nullptr, 0, &feats);
            }
            if (ok) {
                if (ctx.gpu != VK_NULL_HANDLE) gpu_ = ctx.gpu;
                device_ = ctx.device;
                queue_ = ctx.queue;
                queueFamily_ = ctx.queue_family_index;
                presentQueue_ = ctx.presentation_queue ? ctx.presentation_queue : ctx.queue;
                presentFamily_ = ctx.presentation_queue ? ctx.presentation_queue_family_index : ctx.queue_family_index;
                coreCreatedDevice_ = true;
                LOGI("vulkan: device created by the core (queue family %u, present family %u)", queueFamily_, presentFamily_);
            } else {
                LOGW("vulkan: core device negotiation failed, creating a default device");
            }
        }
        if (!ok && !createOwnDevice()) return false;
        if (!coreCreatedDevice_) LOGI("vulkan: default device (queue family %u, present family %u)", queueFamily_, presentFamily_);
        // The negotiated queue may not be able to present to our surface: then presentation goes through the
        // presentation queue the core designated (same-family check below).
        VkBool32 canPresent = VK_FALSE;
        vkGetPhysicalDeviceSurfaceSupportKHR(gpu_, presentFamily_, surface_, &canPresent);
        if (!canPresent) { LOGE("vulkan: presentation queue family %u cannot present to the window", presentFamily_); return false; }

        VkCommandPoolCreateInfo pool{ VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO };
        pool.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
        pool.queueFamilyIndex = queueFamily_;
        VK_CHECK(vkCreateCommandPool(device_, &pool, nullptr, &cmdPool_), "vkCreateCommandPool");
        fillInterface();
    }
    return createSwapchain();
}

void VideoVK::fillInterface() {
    iface_ = {};
    iface_.interface_type = RETRO_HW_RENDER_INTERFACE_VULKAN;
    iface_.interface_version = RETRO_HW_RENDER_INTERFACE_VULKAN_VERSION;
    iface_.handle = this;
    iface_.instance = instance_;
    iface_.gpu = gpu_;
    iface_.device = device_;
    iface_.get_device_proc_addr = vkGetDeviceProcAddr;
    iface_.get_instance_proc_addr = vkGetInstanceProcAddr;
    iface_.queue = queue_;
    iface_.queue_index = queueFamily_;
    iface_.set_image = &VideoVK::cbSetImage;
    iface_.get_sync_index = &VideoVK::cbGetSyncIndex;
    iface_.get_sync_index_mask = &VideoVK::cbGetSyncIndexMask;
    iface_.set_command_buffers = &VideoVK::cbSetCommandBuffers;
    iface_.wait_sync_index = &VideoVK::cbWaitSyncIndex;
    iface_.lock_queue = &VideoVK::cbLockQueue;
    iface_.unlock_queue = &VideoVK::cbUnlockQueue;
    iface_.set_signal_semaphore = &VideoVK::cbSetSignalSemaphore;
}

// ---------------------------------------------------------------- surface / swapchain
bool VideoVK::createSurface(ANativeWindow* window) {
    if (surface_ != VK_NULL_HANDLE && window == window_) return true;
    if (surface_ != VK_NULL_HANDLE) { destroySwapchain(); vkDestroySurfaceKHR(instance_, surface_, nullptr); surface_ = VK_NULL_HANDLE; }
    window_ = window;
    if (!window) return false;
    VkAndroidSurfaceCreateInfoKHR info{ VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR };
    info.window = window;
    VK_CHECK(vkCreateAndroidSurfaceKHR(instance_, &info, nullptr, &surface_), "vkCreateAndroidSurfaceKHR");
    return true;
}

void VideoVK::setWindow(ANativeWindow* window) {
    if (device_ == VK_NULL_HANDLE) { window_ = window; return; }
    if (window == window_ && swapchain_ != VK_NULL_HANDLE) return; // same window: a resize surfaces as OUT_OF_DATE
    if (!createSurface(window)) return;
    createSwapchain();
}

void VideoVK::waitIdleLocked() {
    if (device_ == VK_NULL_HANDLE) return;
    std::lock_guard<std::mutex> lock(queueMutex_); // vkDeviceWaitIdle is a queue operation: exclude core submits
    vkDeviceWaitIdle(device_);
}

bool VideoVK::createSwapchain() {
    if (surface_ == VK_NULL_HANDLE || device_ == VK_NULL_HANDLE) return false;
    // The sync index mask may change with the image count: the core assumes an idle device when it does.
    destroySwapchain();

    VkSurfaceCapabilitiesKHR caps{};
    VK_CHECK(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(gpu_, surface_, &caps), "vkGetPhysicalDeviceSurfaceCapabilitiesKHR");
    uint32_t nf = 0;
    vkGetPhysicalDeviceSurfaceFormatsKHR(gpu_, surface_, &nf, nullptr);
    std::vector<VkSurfaceFormatKHR> formats(nf);
    vkGetPhysicalDeviceSurfaceFormatsKHR(gpu_, surface_, &nf, formats.data());
    VkSurfaceFormatKHR chosen = formats.empty() ? VkSurfaceFormatKHR{ VK_FORMAT_R8G8B8A8_UNORM, VK_COLOR_SPACE_SRGB_NONLINEAR_KHR } : formats[0];
    for (auto& f : formats) {
        if ((f.format == VK_FORMAT_R8G8B8A8_UNORM || f.format == VK_FORMAT_B8G8R8A8_UNORM) && f.colorSpace == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) { chosen = f; break; }
    }
    swapFormat_ = chosen.format;

    extent_ = caps.currentExtent;
    if (extent_.width == 0xFFFFFFFFu) {
        extent_.width = (uint32_t)std::max(1, ANativeWindow_getWidth(window_));
        extent_.height = (uint32_t)std::max(1, ANativeWindow_getHeight(window_));
    }
    if (extent_.width == 0 || extent_.height == 0) { LOGW("vulkan: zero-sized surface, waiting"); return false; }

    uint32_t imageCount = std::max(caps.minImageCount, 3u);
    if (caps.maxImageCount > 0) imageCount = std::min(imageCount, caps.maxImageCount);

    // FIFO is always available; the frame loop already paces to the core's fps.
    VkPresentModeKHR mode = VK_PRESENT_MODE_FIFO_KHR;

    VkSwapchainCreateInfoKHR sc{ VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR };
    sc.surface = surface_;
    sc.minImageCount = imageCount;
    sc.imageFormat = swapFormat_;
    sc.imageColorSpace = chosen.colorSpace;
    sc.imageExtent = extent_;
    sc.imageArrayLayers = 1;
    sc.imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
    uint32_t fams[2] = { queueFamily_, presentFamily_ };
    if (queueFamily_ != presentFamily_) { sc.imageSharingMode = VK_SHARING_MODE_CONCURRENT; sc.queueFamilyIndexCount = 2; sc.pQueueFamilyIndices = fams; }
    else sc.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
    sc.preTransform = (caps.supportedTransforms & VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR) ? VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR : caps.currentTransform;
    sc.compositeAlpha = (caps.supportedCompositeAlpha & VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR) ? VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR : VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR;
    sc.presentMode = mode;
    sc.clipped = VK_TRUE;
    VK_CHECK(vkCreateSwapchainKHR(device_, &sc, nullptr, &swapchain_), "vkCreateSwapchainKHR");

    uint32_t n = 0;
    vkGetSwapchainImagesKHR(device_, swapchain_, &n, nullptr);
    swapImages_.resize(n);
    vkGetSwapchainImagesKHR(device_, swapchain_, &n, swapImages_.data());

    if (renderPass_ == VK_NULL_HANDLE && !createPipeline()) return false;

    swapViews_.resize(n); framebuffers_.resize(n); fences_.resize(n); renderDoneSems_.resize(n); acquireSems_.resize(n);
    descSets_.resize(n); cmdBufs_.resize(n); imageFence_.assign(n, VK_NULL_HANDLE);
    for (uint32_t i = 0; i < n; i++) {
        VkImageViewCreateInfo v{ VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO };
        v.image = swapImages_[i];
        v.viewType = VK_IMAGE_VIEW_TYPE_2D;
        v.format = swapFormat_;
        v.subresourceRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 };
        VK_CHECK(vkCreateImageView(device_, &v, nullptr, &swapViews_[i]), "vkCreateImageView(swapchain)");
        VkFramebufferCreateInfo fb{ VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO };
        fb.renderPass = renderPass_;
        fb.attachmentCount = 1;
        fb.pAttachments = &swapViews_[i];
        fb.width = extent_.width; fb.height = extent_.height; fb.layers = 1;
        VK_CHECK(vkCreateFramebuffer(device_, &fb, nullptr, &framebuffers_[i]), "vkCreateFramebuffer");
        VkFenceCreateInfo fi{ VK_STRUCTURE_TYPE_FENCE_CREATE_INFO };
        fi.flags = VK_FENCE_CREATE_SIGNALED_BIT;
        VK_CHECK(vkCreateFence(device_, &fi, nullptr, &fences_[i]), "vkCreateFence");
        VkSemaphoreCreateInfo si{ VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO };
        VK_CHECK(vkCreateSemaphore(device_, &si, nullptr, &renderDoneSems_[i]), "vkCreateSemaphore");
        VK_CHECK(vkCreateSemaphore(device_, &si, nullptr, &acquireSems_[i]), "vkCreateSemaphore");
    }
    VkDescriptorSetAllocateInfo da{ VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO };
    std::vector<VkDescriptorSetLayout> layouts(n, descLayout_);
    da.descriptorPool = descPool_;
    da.descriptorSetCount = n;
    da.pSetLayouts = layouts.data();
    VK_CHECK(vkAllocateDescriptorSets(device_, &da, descSets_.data()), "vkAllocateDescriptorSets");
    VkCommandBufferAllocateInfo ca{ VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO };
    ca.commandPool = cmdPool_;
    ca.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    ca.commandBufferCount = n;
    VK_CHECK(vkAllocateCommandBuffers(device_, &ca, cmdBufs_.data()), "vkAllocateCommandBuffers");

    syncIndex_ = 0; frameSlot_ = 0; acquired_ = false; swapchainDirty_ = false;
    LOGI("vulkan: swapchain %ux%u x%u (format %d, %s, transform %u/%u)", extent_.width, extent_.height, n,
         (int)swapFormat_, mode == VK_PRESENT_MODE_FIFO_KHR ? "fifo" : "mailbox",
         (unsigned)sc.preTransform, (unsigned)caps.currentTransform);
    return true;
}

void VideoVK::destroySwapchain() {
    if (device_ == VK_NULL_HANDLE) return;
    waitIdleLocked();
    for (auto f : framebuffers_) vkDestroyFramebuffer(device_, f, nullptr);
    for (auto v : swapViews_) vkDestroyImageView(device_, v, nullptr);
    for (auto f : fences_) vkDestroyFence(device_, f, nullptr);
    for (auto s : renderDoneSems_) vkDestroySemaphore(device_, s, nullptr);
    for (auto s : acquireSems_) vkDestroySemaphore(device_, s, nullptr);
    if (!descSets_.empty() && descPool_) vkFreeDescriptorSets(device_, descPool_, (uint32_t)descSets_.size(), descSets_.data());
    if (!cmdBufs_.empty() && cmdPool_) vkFreeCommandBuffers(device_, cmdPool_, (uint32_t)cmdBufs_.size(), cmdBufs_.data());
    framebuffers_.clear(); swapViews_.clear(); fences_.clear(); renderDoneSems_.clear(); acquireSems_.clear();
    descSets_.clear(); cmdBufs_.clear(); swapImages_.clear(); imageFence_.clear();
    if (swapchain_ != VK_NULL_HANDLE) vkDestroySwapchainKHR(device_, swapchain_, nullptr);
    swapchain_ = VK_NULL_HANDLE;
    acquired_ = false;
}

// ---------------------------------------------------------------- blit pipeline
bool VideoVK::createPipeline() {
    VkAttachmentDescription att{};
    att.format = swapFormat_;
    att.samples = VK_SAMPLE_COUNT_1_BIT;
    att.loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
    att.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
    att.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
    att.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
    att.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    att.finalLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
    VkAttachmentReference ref{ 0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL };
    VkSubpassDescription sub{};
    sub.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
    sub.colorAttachmentCount = 1;
    sub.pColorAttachments = &ref;
    VkSubpassDependency dep{};
    dep.srcSubpass = VK_SUBPASS_EXTERNAL;
    dep.dstSubpass = 0;
    dep.srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    dep.dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    dep.srcAccessMask = 0;
    dep.dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
    VkRenderPassCreateInfo rp{ VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO };
    rp.attachmentCount = 1; rp.pAttachments = &att;
    rp.subpassCount = 1; rp.pSubpasses = &sub;
    rp.dependencyCount = 1; rp.pDependencies = &dep;
    VK_CHECK(vkCreateRenderPass(device_, &rp, nullptr, &renderPass_), "vkCreateRenderPass");

    VkDescriptorSetLayoutBinding b{};
    b.binding = 0;
    b.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    b.descriptorCount = 1;
    b.stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;
    VkDescriptorSetLayoutCreateInfo dl{ VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO };
    dl.bindingCount = 1; dl.pBindings = &b;
    VK_CHECK(vkCreateDescriptorSetLayout(device_, &dl, nullptr, &descLayout_), "vkCreateDescriptorSetLayout");

    VkDescriptorPoolSize ps{ VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 16 };
    VkDescriptorPoolCreateInfo dp{ VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO };
    dp.flags = VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT;
    dp.maxSets = 16; dp.poolSizeCount = 1; dp.pPoolSizes = &ps;
    VK_CHECK(vkCreateDescriptorPool(device_, &dp, nullptr, &descPool_), "vkCreateDescriptorPool");

    // The filter parameters live at the end of the same block, read by the fragment stage.
    VkPushConstantRange pc{ VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0, 48 };
    VkPipelineLayoutCreateInfo pl{ VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO };
    pl.setLayoutCount = 1; pl.pSetLayouts = &descLayout_;
    pl.pushConstantRangeCount = 1; pl.pPushConstantRanges = &pc;
    VK_CHECK(vkCreatePipelineLayout(device_, &pl, nullptr, &pipelineLayout_), "vkCreatePipelineLayout");

    VkShaderModuleCreateInfo vs{ VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO };
    vs.codeSize = sizeof kBlitVertSpv; vs.pCode = kBlitVertSpv;
    VkShaderModuleCreateInfo fs{ VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO };
    fs.codeSize = sizeof kBlitFragSpv; fs.pCode = kBlitFragSpv;
    VkShaderModule vmod = VK_NULL_HANDLE, fmod = VK_NULL_HANDLE;
    VK_CHECK(vkCreateShaderModule(device_, &vs, nullptr, &vmod), "vkCreateShaderModule(vert)");
    VK_CHECK(vkCreateShaderModule(device_, &fs, nullptr, &fmod), "vkCreateShaderModule(frag)");

    VkPipelineShaderStageCreateInfo stages[2]{};
    stages[0] = { VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO }; stages[0].stage = VK_SHADER_STAGE_VERTEX_BIT; stages[0].module = vmod; stages[0].pName = "main";
    stages[1] = { VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO }; stages[1].stage = VK_SHADER_STAGE_FRAGMENT_BIT; stages[1].module = fmod; stages[1].pName = "main";
    VkPipelineVertexInputStateCreateInfo vi{ VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO };
    VkPipelineInputAssemblyStateCreateInfo ia{ VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO };
    ia.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP;
    VkPipelineViewportStateCreateInfo vp{ VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO };
    vp.viewportCount = 1; vp.scissorCount = 1;
    VkPipelineRasterizationStateCreateInfo rs{ VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO };
    rs.polygonMode = VK_POLYGON_MODE_FILL; rs.cullMode = VK_CULL_MODE_NONE; rs.frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE; rs.lineWidth = 1.f;
    VkPipelineMultisampleStateCreateInfo ms{ VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO };
    ms.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;
    VkPipelineColorBlendAttachmentState cba{};
    cba.colorWriteMask = VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT;
    VkPipelineColorBlendStateCreateInfo cb{ VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO };
    cb.attachmentCount = 1; cb.pAttachments = &cba;
    VkDynamicState dyn[2] = { VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR };
    VkPipelineDynamicStateCreateInfo ds{ VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO };
    ds.dynamicStateCount = 2; ds.pDynamicStates = dyn;
    VkGraphicsPipelineCreateInfo gp{ VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO };
    gp.stageCount = 2; gp.pStages = stages;
    gp.pVertexInputState = &vi; gp.pInputAssemblyState = &ia; gp.pViewportState = &vp;
    gp.pRasterizationState = &rs; gp.pMultisampleState = &ms; gp.pColorBlendState = &cb; gp.pDynamicState = &ds;
    gp.layout = pipelineLayout_; gp.renderPass = renderPass_; gp.subpass = 0;
    VkResult r = vkCreateGraphicsPipelines(device_, VK_NULL_HANDLE, 1, &gp, nullptr, &pipeline_);
    vkDestroyShaderModule(device_, vmod, nullptr);
    vkDestroyShaderModule(device_, fmod, nullptr);
    if (r != VK_SUCCESS) { LOGE("vulkan: vkCreateGraphicsPipelines failed: %s", vkResultName(r)); return false; }

    VkSamplerCreateInfo sa{ VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO };
    sa.magFilter = sa.minFilter = VK_FILTER_LINEAR;
    sa.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
    sa.addressModeU = sa.addressModeV = sa.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    sa.maxLod = 0.f;
    VK_CHECK(vkCreateSampler(device_, &sa, nullptr, &samplerLinear_), "vkCreateSampler");
    sa.magFilter = sa.minFilter = VK_FILTER_NEAREST;
    VK_CHECK(vkCreateSampler(device_, &sa, nullptr, &samplerNearest_), "vkCreateSampler");
    return true;
}

void VideoVK::destroyPipeline() {
    if (device_ == VK_NULL_HANDLE) return;
    if (samplerLinear_) vkDestroySampler(device_, samplerLinear_, nullptr);
    if (samplerNearest_) vkDestroySampler(device_, samplerNearest_, nullptr);
    if (pipeline_) vkDestroyPipeline(device_, pipeline_, nullptr);
    if (pipelineLayout_) vkDestroyPipelineLayout(device_, pipelineLayout_, nullptr);
    if (descPool_) vkDestroyDescriptorPool(device_, descPool_, nullptr);
    if (descLayout_) vkDestroyDescriptorSetLayout(device_, descLayout_, nullptr);
    if (renderPass_) vkDestroyRenderPass(device_, renderPass_, nullptr);
    samplerLinear_ = samplerNearest_ = VK_NULL_HANDLE; pipeline_ = VK_NULL_HANDLE; pipelineLayout_ = VK_NULL_HANDLE;
    descPool_ = VK_NULL_HANDLE; descLayout_ = VK_NULL_HANDLE; renderPass_ = VK_NULL_HANDLE;
}

// ---------------------------------------------------------------- core callbacks
void VideoVK::cbSetImage(void* h, const retro_vulkan_image* image, uint32_t numSems, const VkSemaphore* sems, uint32_t srcQueueFamily) {
    auto* self = (VideoVK*)h;
    if (!image) { self->haveImage_ = false; return; }
    self->image_ = *image;
    self->haveImage_ = true;
    self->waitSems_.assign(sems, sems + numSems);
    self->srcQueueFamily_ = srcQueueFamily;
}
uint32_t VideoVK::cbGetSyncIndex(void* h) { return ((VideoVK*)h)->syncIndex_; }
uint32_t VideoVK::cbGetSyncIndexMask(void* h) {
    auto* self = (VideoVK*)h;
    uint32_t n = (uint32_t)self->swapImages_.size();
    return n >= 32 ? 0xFFFFFFFFu : ((1u << n) - 1u);
}
void VideoVK::cbSetCommandBuffers(void* h, uint32_t n, const VkCommandBuffer* cmd) {
    auto* self = (VideoVK*)h;
    self->coreCmds_.assign(cmd, cmd + n);
}
void VideoVK::cbWaitSyncIndex(void* h) {
    // "Waits on CPU for device activity for the current sync index to complete": the last submit that used this
    // swapchain image. beginFrame() already waited for it, so this normally returns at once.
    auto* self = (VideoVK*)h;
    if (self->device_ == VK_NULL_HANDLE || self->imageFence_.empty()) return;
    VkFence f = self->imageFence_[self->syncIndex_];
    if (f != VK_NULL_HANDLE) vkWaitForFences(self->device_, 1, &f, VK_TRUE, UINT64_MAX);
}
void VideoVK::cbLockQueue(void* h) { ((VideoVK*)h)->queueMutex_.lock(); }
void VideoVK::cbUnlockQueue(void* h) { ((VideoVK*)h)->queueMutex_.unlock(); }
void VideoVK::cbSetSignalSemaphore(void* h, VkSemaphore s) { ((VideoVK*)h)->signalSem_ = s; }

// ---------------------------------------------------------------- frame
bool VideoVK::beginFrame() {
    if (swapchain_ == VK_NULL_HANDLE) return false;
    if (swapchainDirty_ && !createSwapchain()) return false;
    if (acquired_) return true;
    frameSlot_ = (frameSlot_ + 1) % (uint32_t)acquireSems_.size();
    // The submit made in this slot last time must be done before its acquire semaphore and fence are reused.
    vkWaitForFences(device_, 1, &fences_[frameSlot_], VK_TRUE, UINT64_MAX);
    uint32_t idx = 0;
    VkResult r = vkAcquireNextImageKHR(device_, swapchain_, UINT64_MAX, acquireSems_[frameSlot_], VK_NULL_HANDLE, &idx);
    if (r == VK_ERROR_OUT_OF_DATE_KHR || r == VK_ERROR_SURFACE_LOST_KHR) {
        LOGW("vulkan: acquire: %s, recreating swapchain", vkResultName(r));
        if (r == VK_ERROR_SURFACE_LOST_KHR) { ANativeWindow* w = window_; window_ = nullptr; if (!createSurface(w)) return false; }
        if (!createSwapchain()) return false;
        r = vkAcquireNextImageKHR(device_, swapchain_, UINT64_MAX, acquireSems_[frameSlot_], VK_NULL_HANDLE, &idx);
    }
    if (r != VK_SUCCESS && r != VK_SUBOPTIMAL_KHR) { LOGE("vulkan: vkAcquireNextImageKHR failed: %s", vkResultName(r)); return false; }
    syncIndex_ = idx;
    // Everything that rendered to this swapchain image last time (ours and, through the sync index, the core's
    // per-frame resources) has finished once the fence of that submit is signalled.
    if (imageFence_[idx] != VK_NULL_HANDLE && imageFence_[idx] != fences_[frameSlot_])
        vkWaitForFences(device_, 1, &imageFence_[idx], VK_TRUE, UINT64_MAX);
    imageFence_[idx] = fences_[frameSlot_];
    acquired_ = true;
    return true;
}

void VideoVK::present(const VideoConfig& cfg, float coreAspect, bool haveFrame) {
    if (!acquired_ && !beginFrame()) return;
    const uint32_t i = syncIndex_;
    bool drawImage = haveFrame && haveImage_ && image_.image_view != VK_NULL_HANDLE;

    // Output rectangle: same arithmetic as VideoGL::present / PadGeometry.computeGameRect (top-left origin).
    float sw = (float)extent_.width, sh = (float)extent_.height;
    float aspect = coreAspect > 0 ? coreAspect : 4.f / 3.f;
    bool rotated = (cfg.rotation % 2) == 1;
    if (rotated) aspect = 1.0f / aspect;
    float vpX = std::min(std::max(cfg.vpX, 0.f), 1.f), vpY = std::min(std::max(cfg.vpY, 0.f), 1.f);
    float vpW = std::min(std::max(cfg.vpW, 0.05f), 1.f - vpX), vpH = std::min(std::max(cfg.vpH, 0.05f), 1.f - vpY);
    float rx = vpX * sw, ry = vpY * sh, rw = vpW * sw, rh = vpH * sh;
    float outW = rw, outH = rh;
    if (cfg.aspect != AspectMode::Stretch) {
        if (outW / outH > aspect) outW = outH * aspect; else outH = outW / aspect;
    }
    int vx = (int)(rx + (rw - outW) / 2), vy = (int)(ry + (rh - outH) / 2);
    int vw = std::max(1, (int)outW), vh = std::max(1, (int)outH);
    lastViewport_[0] = vx; lastViewport_[1] = vy; lastViewport_[2] = vw; lastViewport_[3] = vh;

    VkCommandBuffer cmd = cmdBufs_[i];
    vkResetCommandBuffer(cmd, 0);
    VkCommandBufferBeginInfo bi{ VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO };
    bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vkBeginCommandBuffer(cmd, &bi);

    bool ownershipTransfer = drawImage && srcQueueFamily_ != VK_QUEUE_FAMILY_IGNORED && srcQueueFamily_ != queueFamily_;
    if (drawImage) {
        // Make the core's rendering visible to our fragment shader (the libretro doc's recommended barrier); this
        // is also the acquire half of a queue-family ownership transfer when the core renders on another family.
        VkImageMemoryBarrier b{ VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER };
        b.srcAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT;
        b.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        b.oldLayout = image_.image_layout;
        b.newLayout = image_.image_layout;
        b.srcQueueFamilyIndex = ownershipTransfer ? srcQueueFamily_ : VK_QUEUE_FAMILY_IGNORED;
        b.dstQueueFamilyIndex = ownershipTransfer ? queueFamily_ : VK_QUEUE_FAMILY_IGNORED;
        b.image = image_.create_info.image;
        b.subresourceRange = image_.create_info.subresourceRange;
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, 0, nullptr, 0, nullptr, 1, &b);

        VkDescriptorImageInfo di{};
        di.sampler = cfg.linearFilter ? samplerLinear_ : samplerNearest_;
        di.imageView = image_.image_view;
        di.imageLayout = image_.image_layout;
        VkWriteDescriptorSet wd{ VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET };
        wd.dstSet = descSets_[i];
        wd.dstBinding = 0;
        wd.descriptorCount = 1;
        wd.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        wd.pImageInfo = &di;
        vkUpdateDescriptorSets(device_, 1, &wd, 0, nullptr); // safe: this set's last use finished (fence in beginFrame)
    }

    VkClearValue clear{}; clear.color = { { 0.f, 0.f, 0.f, 1.f } };
    VkRenderPassBeginInfo rp{ VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO };
    rp.renderPass = renderPass_;
    rp.framebuffer = framebuffers_[i];
    rp.renderArea = { { 0, 0 }, extent_ };
    rp.clearValueCount = 1; rp.pClearValues = &clear;
    vkCmdBeginRenderPass(cmd, &rp, VK_SUBPASS_CONTENTS_INLINE);
    if (drawImage) {
        VkViewport viewport{ (float)vx, (float)vy, (float)vw, (float)vh, 0.f, 1.f }; // Vulkan: y down, top-left origin
        VkRect2D scissor{ { std::max(0, vx), std::max(0, vy) }, { (uint32_t)vw, (uint32_t)vh } };
        vkCmdSetViewport(cmd, 0, 1, &viewport);
        vkCmdSetScissor(cmd, 0, 1, &scissor);
        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline_);
        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout_, 0, 1, &descSets_[i], 0, nullptr);
        // Vulkan images are top-down like the screen: no flip. Rotation as in the GL path (clockwise quarter turns).
        // SET_ROTATION counts counter-clockwise (libretro.h), and clip space is y-up, so the angle is
        // positive. Turning it the other way left vertical arcade boards (Strikers 1945 II) upside down.
        float c = std::cos((float)cfg.rotation * (float)M_PI_2), s = std::sin((float)cfg.rotation * (float)M_PI_2);
        float pc[12] = { 0.f, 0.f, 1.f, 1.f, c, s, 0.f, 0.f, (float)cfg.filter, cfg.filterStrength, 0.f, 0.f };
        vkCmdPushConstants(cmd, pipelineLayout_, VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof pc, pc);
        vkCmdDraw(cmd, 4, 1, 0, 0);
    }
    vkCmdEndRenderPass(cmd);

    if (drawImage) {
        // The core must not start writing the image again before we are done sampling it (queue-wide execution
        // dependency: later submissions on this queue respect it); also the release half of an ownership transfer.
        VkImageMemoryBarrier b{ VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER };
        b.srcAccessMask = VK_ACCESS_SHADER_READ_BIT;
        b.dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT;
        b.oldLayout = image_.image_layout;
        b.newLayout = image_.image_layout;
        b.srcQueueFamilyIndex = ownershipTransfer ? queueFamily_ : VK_QUEUE_FAMILY_IGNORED;
        b.dstQueueFamilyIndex = ownershipTransfer ? srcQueueFamily_ : VK_QUEUE_FAMILY_IGNORED;
        b.image = image_.create_info.image;
        b.subresourceRange = image_.create_info.subresourceRange;
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, 0, nullptr, 0, nullptr, 1, &b);
    }
    vkEndCommandBuffer(cmd);

    // Submit: the core's command buffers first (set_command_buffers), then ours. Wait for the acquire semaphore and
    // for the semaphores handed over with set_image (once, and not when set_command_buffers is used).
    std::vector<VkCommandBuffer> cmds;
    cmds.insert(cmds.end(), coreCmds_.begin(), coreCmds_.end());
    cmds.push_back(cmd);
    std::vector<VkSemaphore> waits;
    std::vector<VkPipelineStageFlags> waitStages;
    waits.push_back(acquireSems_[frameSlot_]); waitStages.push_back(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT);
    if (drawImage && coreCmds_.empty()) {
        for (auto s : waitSems_) { waits.push_back(s); waitStages.push_back(VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT); }
    }
    waitSems_.clear();
    coreCmds_.clear();
    std::vector<VkSemaphore> signals{ renderDoneSems_[i] };
    if (signalSem_ != VK_NULL_HANDLE) { signals.push_back(signalSem_); signalSem_ = VK_NULL_HANDLE; }

    VkSubmitInfo si{ VK_STRUCTURE_TYPE_SUBMIT_INFO };
    si.waitSemaphoreCount = (uint32_t)waits.size(); si.pWaitSemaphores = waits.data(); si.pWaitDstStageMask = waitStages.data();
    si.commandBufferCount = (uint32_t)cmds.size(); si.pCommandBuffers = cmds.data();
    si.signalSemaphoreCount = (uint32_t)signals.size(); si.pSignalSemaphores = signals.data();
    {
        std::lock_guard<std::mutex> lock(queueMutex_);
        vkResetFences(device_, 1, &fences_[frameSlot_]);
        VkResult r = vkQueueSubmit(queue_, 1, &si, fences_[frameSlot_]);
        if (r != VK_SUCCESS) LOGE("vulkan: vkQueueSubmit failed: %s", vkResultName(r));
        VkPresentInfoKHR pi{ VK_STRUCTURE_TYPE_PRESENT_INFO_KHR };
        pi.waitSemaphoreCount = 1; pi.pWaitSemaphores = &renderDoneSems_[i];
        pi.swapchainCount = 1; pi.pSwapchains = &swapchain_; pi.pImageIndices = &i;
        r = vkQueuePresentKHR(presentQueue_, &pi);
        // SUBOPTIMAL is not a reason to rebuild: the image is presented correctly, the surface just would
        // prefer other parameters. Adreno returns it on every present when the swapchain's preTransform is
        // IDENTITY and the display is rotated, and rebuilding does not change that - ARMSX2 on a Fold 7 spent
        // every frame recreating the swapchain under the core's feet until the process died.
        if (r == VK_ERROR_OUT_OF_DATE_KHR) swapchainDirty_ = true;
        else if (r != VK_SUCCESS && r != VK_SUBOPTIMAL_KHR) LOGE("vulkan: vkQueuePresentKHR failed: %s", vkResultName(r));
    }
    acquired_ = false;
}

// ---------------------------------------------------------------- readback
bool VideoVK::createStaging(uint32_t w, uint32_t h) {
    if (stagingBuf_ && stagingW_ == w && stagingH_ == h) return true;
    if (stagingBuf_) { vkDestroyBuffer(device_, stagingBuf_, nullptr); vkFreeMemory(device_, stagingMem_, nullptr); stagingBuf_ = VK_NULL_HANDLE; }
    VkBufferCreateInfo bi{ VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO };
    bi.size = (VkDeviceSize)w * h * 4;
    bi.usage = VK_BUFFER_USAGE_TRANSFER_DST_BIT;
    VK_CHECK(vkCreateBuffer(device_, &bi, nullptr, &stagingBuf_), "vkCreateBuffer(staging)");
    VkMemoryRequirements req{};
    vkGetBufferMemoryRequirements(device_, stagingBuf_, &req);
    VkPhysicalDeviceMemoryProperties mp{};
    vkGetPhysicalDeviceMemoryProperties(gpu_, &mp);
    uint32_t type = UINT32_MAX;
    for (uint32_t t = 0; t < mp.memoryTypeCount; t++) {
        bool ok = (req.memoryTypeBits & (1u << t)) &&
            (mp.memoryTypes[t].propertyFlags & (VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)) ==
            (VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        if (ok) { type = t; break; }
    }
    if (type == UINT32_MAX) { LOGE("vulkan: no host-visible memory for readback"); return false; }
    VkMemoryAllocateInfo ai{ VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO };
    ai.allocationSize = req.size; ai.memoryTypeIndex = type;
    VK_CHECK(vkAllocateMemory(device_, &ai, nullptr, &stagingMem_), "vkAllocateMemory(staging)");
    VK_CHECK(vkBindBufferMemory(device_, stagingBuf_, stagingMem_, 0), "vkBindBufferMemory");
    stagingW_ = w; stagingH_ = h;
    return true;
}

bool VideoVK::readback(std::vector<uint32_t>& out, int& w, int& h) {
    // Copies the last swapchain image we rendered (letterboxed output), like the GL path's glReadPixels of the viewport.
    if (!ready() || swapImages_.empty() || lastViewport_[2] <= 0 || lastViewport_[3] <= 0) return false;
    uint32_t i = syncIndex_;
    if (imageFence_[i] != VK_NULL_HANDLE) vkWaitForFences(device_, 1, &imageFence_[i], VK_TRUE, UINT64_MAX);
    w = lastViewport_[2]; h = lastViewport_[3];
    if (!createStaging((uint32_t)w, (uint32_t)h)) return false;
    VkCommandBufferAllocateInfo ca{ VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO };
    ca.commandPool = cmdPool_; ca.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY; ca.commandBufferCount = 1;
    VkCommandBuffer cmd = VK_NULL_HANDLE;
    VK_CHECK(vkAllocateCommandBuffers(device_, &ca, &cmd), "vkAllocateCommandBuffers(readback)");
    VkCommandBufferBeginInfo bi{ VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO };
    bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vkBeginCommandBuffer(cmd, &bi);
    VkImageMemoryBarrier b{ VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER };
    b.srcAccessMask = VK_ACCESS_MEMORY_READ_BIT; b.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
    b.oldLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR; b.newLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
    b.srcQueueFamilyIndex = b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    b.image = swapImages_[i]; b.subresourceRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 };
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 1, &b);
    VkBufferImageCopy region{};
    region.imageSubresource = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1 };
    region.imageOffset = { std::max(0, lastViewport_[0]), std::max(0, lastViewport_[1]), 0 };
    region.imageExtent = { (uint32_t)w, (uint32_t)h, 1 };
    vkCmdCopyImageToBuffer(cmd, swapImages_[i], VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, stagingBuf_, 1, &region);
    std::swap(b.oldLayout, b.newLayout); std::swap(b.srcAccessMask, b.dstAccessMask);
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, 0, nullptr, 0, nullptr, 1, &b);
    vkEndCommandBuffer(cmd);
    VkSubmitInfo si{ VK_STRUCTURE_TYPE_SUBMIT_INFO };
    si.commandBufferCount = 1; si.pCommandBuffers = &cmd;
    {
        std::lock_guard<std::mutex> lock(queueMutex_);
        vkQueueSubmit(queue_, 1, &si, VK_NULL_HANDLE);
        vkQueueWaitIdle(queue_);
    }
    vkFreeCommandBuffers(device_, cmdPool_, 1, &cmd);
    void* mapped = nullptr;
    VK_CHECK(vkMapMemory(device_, stagingMem_, 0, VK_WHOLE_SIZE, 0, &mapped), "vkMapMemory");
    out.resize((size_t)w * h);
    memcpy(out.data(), mapped, out.size() * 4);
    vkUnmapMemory(device_, stagingMem_);
    if (swapFormat_ == VK_FORMAT_B8G8R8A8_UNORM) {
        for (auto& p : out) p = (p & 0xFF00FF00u) | ((p & 0xFFu) << 16) | ((p >> 16) & 0xFFu);
    }
    for (auto& p : out) p |= 0xFF000000u; // memory order R,G,B,A -> Kotlin expects opaque ABGR-in-int like the GL path
    return true;
}

// ---------------------------------------------------------------- teardown
void VideoVK::destroy() {
    if (device_ != VK_NULL_HANDLE) {
        waitIdleLocked();
        destroySwapchain();
        if (stagingBuf_) { vkDestroyBuffer(device_, stagingBuf_, nullptr); vkFreeMemory(device_, stagingMem_, nullptr); stagingBuf_ = VK_NULL_HANDLE; stagingMem_ = VK_NULL_HANDLE; }
        destroyPipeline();
        if (cmdPool_) vkDestroyCommandPool(device_, cmdPool_, nullptr);
        cmdPool_ = VK_NULL_HANDLE;
        // The core frees its auxiliary device resources first; the device itself is ours (libretro_vulkan.h).
        if (coreCreatedDevice_ && nego_ && nego_->destroy_device) nego_->destroy_device();
        vkDestroyDevice(device_, nullptr);
        device_ = VK_NULL_HANDLE; queue_ = presentQueue_ = VK_NULL_HANDLE;
    }
    if (surface_ != VK_NULL_HANDLE) { vkDestroySurfaceKHR(instance_, surface_, nullptr); surface_ = VK_NULL_HANDLE; }
    if (instance_ != VK_NULL_HANDLE) { vkDestroyInstance(instance_, nullptr); instance_ = VK_NULL_HANDLE; }
    gpu_ = VK_NULL_HANDLE; window_ = nullptr; nego_ = nullptr; coreCreatedDevice_ = false;
    haveImage_ = false; image_ = {}; waitSems_.clear(); coreCmds_.clear(); signalSem_ = VK_NULL_HANDLE;
    iface_ = {};
}
