#include "vulkanbridge.h"

#include <GLES2/gl2ext.h>
#include <dlfcn.h>

#include <algorithm>
#include <cstring>
#include <vector>

#include "log.h"

namespace libretrodroid {

namespace {

// What importing an AHardwareBuffer needs of a device, and what those need where the device is older
// than Vulkan 1.1 (where they are core, listing them is still allowed).
const char *const REQUIRED_EXTENSIONS[] = {
    VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME,
    VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME,
};
const char *const HELPER_EXTENSIONS[] = {
    VK_KHR_SAMPLER_YCBCR_CONVERSION_EXTENSION_NAME,
    VK_KHR_EXTERNAL_MEMORY_EXTENSION_NAME,
    VK_KHR_DEDICATED_ALLOCATION_EXTENSION_NAME,
    VK_KHR_GET_MEMORY_REQUIREMENTS_2_EXTENSION_NAME,
    VK_KHR_MAINTENANCE1_EXTENSION_NAME,
    VK_KHR_BIND_MEMORY_2_EXTENSION_NAME,
};

// A wait longer than this is a device that stopped: the game goes back to GL.
constexpr uint64_t FENCE_TIMEOUT_NS = 2'000'000'000ULL;
// A forced lost device (Forced::LOST) comes after this many frames: the game shows first.
constexpr unsigned FORCED_LOST_AFTER = 300;
// The probe's buffer: any size will do.
constexpr unsigned PROBE_SIZE = 64;

AHardwareBuffer *allocateBuffer(unsigned width, unsigned height) {
    AHardwareBuffer_Desc desc {};
    desc.width = width;
    desc.height = height;
    desc.layers = 1;
    desc.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
    desc.usage = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE | AHARDWAREBUFFER_USAGE_GPU_FRAMEBUFFER;
    AHardwareBuffer *buffer = nullptr;
    return AHardwareBuffer_allocate(&desc, &buffer) == 0 ? buffer : nullptr;
}

bool copiesAsIs(VkFormat format) {
    return format == VK_FORMAT_R8G8B8A8_UNORM || format == VK_FORMAT_R8G8B8A8_SRGB;
}

}

void VulkanBridge::offer(bool offer, Forced force) {
    offered = offer;
    forced = offer ? force : Forced::NONE;
    if (offered) LOGI("Vulkan offered to the core%s", forced == Forced::NONE ? "" : " (a failure forced)");
}

void VulkanBridge::setNegotiation(const retro_hw_render_context_negotiation_interface_vulkan *value) {
    negotiation = value;
}

bool VulkanBridge::fail(const std::string &why) {
    if (failure.empty()) {
        failure = why;
        LOGE("Vulkan: %s", why.c_str());
    }
    return false;
}

bool VulkanBridge::loadLoader() {
    if (getInstanceProcAddr != nullptr) return true;
    loader = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    if (loader == nullptr) return false;
    getInstanceProcAddr = reinterpret_cast<PFN_vkGetInstanceProcAddr>(dlsym(loader, "vkGetInstanceProcAddr"));
    if (getInstanceProcAddr == nullptr) return false;
    vkCreateInstance = reinterpret_cast<PFN_vkCreateInstance>(getInstanceProcAddr(VK_NULL_HANDLE, "vkCreateInstance"));
    vkEnumerateInstanceVersion = reinterpret_cast<PFN_vkEnumerateInstanceVersion>(getInstanceProcAddr(VK_NULL_HANDLE, "vkEnumerateInstanceVersion"));
    return vkCreateInstance != nullptr;
}

bool VulkanBridge::makeInstance(const VkApplicationInfo *app) {
    uint32_t loaderVersion = VK_API_VERSION_1_0;
    if (vkEnumerateInstanceVersion != nullptr) vkEnumerateInstanceVersion(&loaderVersion);
    if (loaderVersion < VK_API_VERSION_1_1) return false;

    VkApplicationInfo info {};
    if (app != nullptr) info = *app;
    info.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    info.pNext = nullptr;
    if (info.pApplicationName == nullptr) info.pApplicationName = "libretrodroid";
    // External memory needs 1.1; never more than the loader has.
    info.apiVersion = std::min(std::max(info.apiVersion, (uint32_t) VK_API_VERSION_1_1), loaderVersion);

    VkInstanceCreateInfo create {};
    create.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    create.pApplicationInfo = &info;
    if (vkCreateInstance(&create, nullptr, &instance) != VK_SUCCESS) return false;
    vkDestroyInstance = reinterpret_cast<PFN_vkDestroyInstance>(getInstanceProcAddr(instance, "vkDestroyInstance"));
    return true;
}

VkInstance VKAPI_CALL VulkanBridge::createInstanceWrapper(void *opaque, const VkInstanceCreateInfo *info) {
    auto *bridge = static_cast<VulkanBridge*>(opaque);
    VkInstance made = VK_NULL_HANDLE;
    if (bridge->vkCreateInstance(info, nullptr, &made) != VK_SUCCESS) return VK_NULL_HANDLE;
    return made;
}

bool VulkanBridge::pickGpu() {
#define INSTANCE_FN(name) name = reinterpret_cast<PFN_##name>(getInstanceProcAddr(instance, #name))
    INSTANCE_FN(vkDestroyInstance);
    INSTANCE_FN(vkEnumeratePhysicalDevices);
    INSTANCE_FN(vkGetPhysicalDeviceProperties);
    INSTANCE_FN(vkGetPhysicalDeviceQueueFamilyProperties);
    INSTANCE_FN(vkEnumerateDeviceExtensionProperties);
    INSTANCE_FN(vkCreateDevice);
    INSTANCE_FN(vkGetDeviceProcAddr);
#undef INSTANCE_FN
    uint32_t count = 1;
    VkPhysicalDevice first = VK_NULL_HANDLE;
    VkResult result = vkEnumeratePhysicalDevices(instance, &count, &first);
    if ((result != VK_SUCCESS && result != VK_INCOMPLETE) || count == 0) return false;
    gpu = first;
    VkPhysicalDeviceProperties properties;
    vkGetPhysicalDeviceProperties(gpu, &properties);
    LOGI("Vulkan GPU: %s, Vulkan %u.%u", properties.deviceName,
         VK_API_VERSION_MAJOR(properties.apiVersion), VK_API_VERSION_MINOR(properties.apiVersion));
    return true;
}

bool VulkanBridge::deviceHasExtensions(VkPhysicalDevice physical, std::string &missing) {
    uint32_t count = 0;
    vkEnumerateDeviceExtensionProperties(physical, nullptr, &count, nullptr);
    std::vector<VkExtensionProperties> available(count);
    vkEnumerateDeviceExtensionProperties(physical, nullptr, &count, available.data());
    for (auto name : REQUIRED_EXTENSIONS) {
        bool found = std::any_of(available.begin(), available.end(), [&](auto &it) { return strcmp(it.extensionName, name) == 0; });
        if (!found) {
            missing = name;
            return false;
        }
    }
    return true;
}

VkDevice VKAPI_CALL VulkanBridge::createDeviceWrapper(VkPhysicalDevice gpu, void *opaque, const VkDeviceCreateInfo *info) {
    return static_cast<VulkanBridge*>(opaque)->makeDevice(gpu, info);
}

VkDevice VulkanBridge::makeDevice(VkPhysicalDevice physical, const VkDeviceCreateInfo *info) {
    // The core's extensions, and the ones the buffer import needs that the device has.
    uint32_t count = 0;
    vkEnumerateDeviceExtensionProperties(physical, nullptr, &count, nullptr);
    std::vector<VkExtensionProperties> available(count);
    vkEnumerateDeviceExtensionProperties(physical, nullptr, &count, available.data());
    auto has = [&](const char *name) {
        return std::any_of(available.begin(), available.end(), [&](auto &it) { return strcmp(it.extensionName, name) == 0; });
    };
    std::vector<const char*> extensions(info->ppEnabledExtensionNames, info->ppEnabledExtensionNames + info->enabledExtensionCount);
    auto add = [&](const char *name) {
        bool listed = std::any_of(extensions.begin(), extensions.end(), [&](auto it) { return strcmp(it, name) == 0; });
        if (!listed && has(name)) extensions.push_back(name);
    };
    for (auto name : REQUIRED_EXTENSIONS) add(name);
    for (auto name : HELPER_EXTENSIONS) add(name);

    VkDeviceCreateInfo create = *info;
    create.enabledExtensionCount = (uint32_t) extensions.size();
    create.ppEnabledExtensionNames = extensions.data();
    VkDevice made = VK_NULL_HANDLE;
    if (vkCreateDevice(physical, &create, nullptr, &made) != VK_SUCCESS) return VK_NULL_HANDLE;
    return made;
}

bool VulkanBridge::makeOwnDevice() {
    // No negotiation from the core: one queue that draws and computes, on a device made here.
    uint32_t count = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(gpu, &count, nullptr);
    std::vector<VkQueueFamilyProperties> families(count);
    vkGetPhysicalDeviceQueueFamilyProperties(gpu, &count, families.data());
    auto wanted = VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT;
    auto found = std::find_if(families.begin(), families.end(), [&](auto &it) { return (it.queueFlags & wanted) == wanted; });
    if (found == families.end()) return false;
    queueFamily = (uint32_t) (found - families.begin());

    float priority = 1.0F;
    VkDeviceQueueCreateInfo queueInfo {};
    queueInfo.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    queueInfo.queueFamilyIndex = queueFamily;
    queueInfo.queueCount = 1;
    queueInfo.pQueuePriorities = &priority;
    VkDeviceCreateInfo info {};
    info.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    info.queueCreateInfoCount = 1;
    info.pQueueCreateInfos = &queueInfo;
    device = makeDevice(gpu, &info);
    if (device == VK_NULL_HANDLE) return false;
    vkGetDeviceQueue = reinterpret_cast<PFN_vkGetDeviceQueue>(vkGetDeviceProcAddr(device, "vkGetDeviceQueue"));
    vkGetDeviceQueue(device, queueFamily, 0, &queue);
    return true;
}

bool VulkanBridge::loadDevice() {
#define DEVICE_FN(name) name = reinterpret_cast<PFN_##name>(vkGetDeviceProcAddr(device, #name)); if (name == nullptr) return false
    DEVICE_FN(vkDestroyDevice);
    DEVICE_FN(vkGetDeviceQueue);
    DEVICE_FN(vkDeviceWaitIdle);
    DEVICE_FN(vkCreateCommandPool);
    DEVICE_FN(vkDestroyCommandPool);
    DEVICE_FN(vkAllocateCommandBuffers);
    DEVICE_FN(vkBeginCommandBuffer);
    DEVICE_FN(vkEndCommandBuffer);
    DEVICE_FN(vkResetCommandBuffer);
    DEVICE_FN(vkCmdPipelineBarrier);
    DEVICE_FN(vkCmdCopyImage);
    DEVICE_FN(vkCmdBlitImage);
    DEVICE_FN(vkQueueSubmit);
    DEVICE_FN(vkCreateFence);
    DEVICE_FN(vkDestroyFence);
    DEVICE_FN(vkWaitForFences);
    DEVICE_FN(vkResetFences);
    DEVICE_FN(vkCreateImage);
    DEVICE_FN(vkDestroyImage);
    DEVICE_FN(vkAllocateMemory);
    DEVICE_FN(vkFreeMemory);
    DEVICE_FN(vkBindImageMemory);
    DEVICE_FN(vkGetAndroidHardwareBufferPropertiesANDROID);
#undef DEVICE_FN
    return true;
}

bool VulkanBridge::importBuffer(AHardwareBuffer *buffer, VkImage &image, VkDeviceMemory &memory) {
    AHardwareBuffer_Desc desc {};
    AHardwareBuffer_describe(buffer, &desc);

    VkAndroidHardwareBufferFormatPropertiesANDROID format {};
    format.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID;
    VkAndroidHardwareBufferPropertiesANDROID properties {};
    properties.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID;
    properties.pNext = &format;
    if (vkGetAndroidHardwareBufferPropertiesANDROID(device, buffer, &properties) != VK_SUCCESS) return false;
    if (format.format != VK_FORMAT_R8G8B8A8_UNORM || properties.memoryTypeBits == 0) return false;

    VkExternalMemoryImageCreateInfo external {};
    external.sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO;
    external.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;
    VkImageCreateInfo imageInfo {};
    imageInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    imageInfo.pNext = &external;
    imageInfo.imageType = VK_IMAGE_TYPE_2D;
    imageInfo.format = VK_FORMAT_R8G8B8A8_UNORM;
    imageInfo.extent = {desc.width, desc.height, 1};
    imageInfo.mipLevels = 1;
    imageInfo.arrayLayers = 1;
    imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
    imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    imageInfo.usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
    imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    if (vkCreateImage(device, &imageInfo, nullptr, &image) != VK_SUCCESS) return false;

    VkMemoryDedicatedAllocateInfo dedicated {};
    dedicated.sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO;
    dedicated.image = image;
    VkImportAndroidHardwareBufferInfoANDROID import {};
    import.sType = VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID;
    import.pNext = &dedicated;
    import.buffer = buffer;
    VkMemoryAllocateInfo allocate {};
    allocate.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocate.pNext = &import;
    allocate.allocationSize = properties.allocationSize;
    allocate.memoryTypeIndex = (uint32_t) __builtin_ctz(properties.memoryTypeBits);
    if (vkAllocateMemory(device, &allocate, nullptr, &memory) != VK_SUCCESS) {
        vkDestroyImage(device, image, nullptr);
        image = VK_NULL_HANDLE;
        return false;
    }
    if (vkBindImageMemory(device, image, memory, 0) != VK_SUCCESS) {
        vkDestroyImage(device, image, nullptr);
        vkFreeMemory(device, memory, nullptr);
        image = VK_NULL_HANDLE;
        memory = VK_NULL_HANDLE;
        return false;
    }
    return true;
}

bool VulkanBridge::probe() {
    if (probed >= 0) return probed == 1;
    probed = 0;
    if (forced == Forced::PROBE) {
        LOGW("Vulkan: the probe was made to fail; the core gets GL");
        return false;
    }
    if (!loadLoader()) {
        LOGW("Vulkan: no loader; the core gets GL");
        return false;
    }
    std::string missing;
    bool ok = makeInstance(nullptr) && pickGpu() && deviceHasExtensions(gpu, missing);
    if (ok) ok = makeOwnDevice() && loadDevice();
    if (ok) {
        VkImage image = VK_NULL_HANDLE;
        VkDeviceMemory memory = VK_NULL_HANDLE;
        AHardwareBuffer *buffer = allocateBuffer(PROBE_SIZE, PROBE_SIZE);
        ok = buffer != nullptr && importBuffer(buffer, image, memory);
        if (image != VK_NULL_HANDLE) vkDestroyImage(device, image, nullptr);
        if (memory != VK_NULL_HANDLE) vkFreeMemory(device, memory, nullptr);
        if (buffer != nullptr) AHardwareBuffer_release(buffer);
    }
    if (!ok) LOGW("Vulkan: this device can't share a frame with GL%s%s; the core gets GL", missing.empty() ? "" : ", no ", missing.c_str());
    destroyProbe();
    probed = ok ? 1 : 0;
    if (ok) LOGI("Vulkan: the probe passed");
    return ok;
}

void VulkanBridge::destroyProbe() {
    if (device != VK_NULL_HANDLE && vkDestroyDevice != nullptr) vkDestroyDevice(device, nullptr);
    if (instance != VK_NULL_HANDLE && vkDestroyInstance != nullptr) vkDestroyInstance(instance, nullptr);
    device = VK_NULL_HANDLE;
    instance = VK_NULL_HANDLE;
    gpu = VK_NULL_HANDLE;
    queue = VK_NULL_HANDLE;
}

bool VulkanBridge::createContext() {
    if (hasContext()) return true;
    if (forced == Forced::DEVICE) return fail("the device was made to fail");
    if (!loadLoader()) return fail("no Vulkan loader");

    const VkApplicationInfo *app = nullptr;
    if (negotiation != nullptr && negotiation->get_application_info != nullptr) app = negotiation->get_application_info();
    bool v2 = negotiation != nullptr && negotiation->interface_version >= 2;
    if (v2 && negotiation->create_instance != nullptr) {
        instance = negotiation->create_instance(getInstanceProcAddr, app, createInstanceWrapper, this);
        if (instance == VK_NULL_HANDLE) return fail("the core's instance wasn't made");
    } else if (!makeInstance(app)) {
        return fail("no Vulkan 1.1 instance");
    }
    if (!pickGpu()) return fail("no GPU");
    std::string missing;
    if (!deviceHasExtensions(gpu, missing)) return fail("the GPU has no " + missing);

    retro_vulkan_context context {};
    if (v2 && negotiation->create_device2 != nullptr) {
        coreMadeDevice = negotiation->create_device2(&context, instance, gpu, VK_NULL_HANDLE, getInstanceProcAddr, createDeviceWrapper, this);
        if (!coreMadeDevice) return fail("the core's device wasn't made");
    } else if (negotiation != nullptr && negotiation->create_device != nullptr) {
        std::vector<const char*> extensions(std::begin(REQUIRED_EXTENSIONS), std::end(REQUIRED_EXTENSIONS));
        VkPhysicalDeviceFeatures features {};
        coreMadeDevice = negotiation->create_device(&context, instance, gpu, VK_NULL_HANDLE, getInstanceProcAddr,
                                                    extensions.data(), (unsigned) extensions.size(), nullptr, 0, &features);
        if (!coreMadeDevice) return fail("the core's device wasn't made");
    }
    if (coreMadeDevice) {
        device = context.device;
        queue = context.queue;
        queueFamily = context.queue_family_index;
        if (context.gpu != VK_NULL_HANDLE) gpu = context.gpu;
    } else if (!makeOwnDevice()) {
        return fail("no device");
    }
    if (!loadDevice()) return fail("the device lacks a function the bridge uses");
    if (!makeFrameObjects()) return fail("no command buffers or fences");

    vulkanInterface = {};
    vulkanInterface.interface_type = RETRO_HW_RENDER_INTERFACE_VULKAN;
    vulkanInterface.interface_version = RETRO_HW_RENDER_INTERFACE_VULKAN_VERSION;
    vulkanInterface.handle = this;
    vulkanInterface.instance = instance;
    vulkanInterface.gpu = gpu;
    vulkanInterface.device = device;
    vulkanInterface.get_device_proc_addr = vkGetDeviceProcAddr;
    vulkanInterface.get_instance_proc_addr = getInstanceProcAddr;
    vulkanInterface.queue = queue;
    vulkanInterface.queue_index = queueFamily;
    vulkanInterface.set_image = setImage;
    vulkanInterface.get_sync_index = getSyncIndex;
    vulkanInterface.get_sync_index_mask = getSyncIndexMask;
    vulkanInterface.set_command_buffers = setCommandBuffers;
    vulkanInterface.wait_sync_index = waitSyncIndex;
    vulkanInterface.lock_queue = lockQueue;
    vulkanInterface.unlock_queue = unlockQueue;
    vulkanInterface.set_signal_semaphore = setSignalSemaphore;
    LOGI("Vulkan context made (%s), queue family %u", coreMadeDevice ? "the core's device" : "a device of its own", queueFamily);
    return true;
}

bool VulkanBridge::makeFrameObjects() {
    VkCommandPoolCreateInfo poolInfo {};
    poolInfo.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    poolInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    poolInfo.queueFamilyIndex = queueFamily;
    if (vkCreateCommandPool(device, &poolInfo, nullptr, &commandPool) != VK_SUCCESS) return false;
    VkCommandBufferAllocateInfo allocate {};
    allocate.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    allocate.commandPool = commandPool;
    allocate.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    allocate.commandBufferCount = Handover::SLOTS;
    if (vkAllocateCommandBuffers(device, &allocate, commandBuffers.data()) != VK_SUCCESS) return false;
    VkFenceCreateInfo fenceInfo {};
    fenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    for (auto &fence : fences) {
        if (vkCreateFence(device, &fenceInfo, nullptr, &fence) != VK_SUCCESS) return false;
    }
    return true;
}

bool VulkanBridge::makeGlSide() {
    if (eglCreateImage == nullptr) {
        eglGetNativeClientBuffer = reinterpret_cast<PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC>(eglGetProcAddress("eglGetNativeClientBufferANDROID"));
        eglCreateImage = reinterpret_cast<PFNEGLCREATEIMAGEKHRPROC>(eglGetProcAddress("eglCreateImageKHR"));
        eglDestroyImage = reinterpret_cast<PFNEGLDESTROYIMAGEKHRPROC>(eglGetProcAddress("eglDestroyImageKHR"));
        glEGLImageTargetTexture2D = reinterpret_cast<void (*)(GLenum, void*)>(eglGetProcAddress("glEGLImageTargetTexture2DOES"));
    }
    if (eglGetNativeClientBuffer == nullptr || eglCreateImage == nullptr || glEGLImageTargetTexture2D == nullptr) return false;

    EGLint attributes[] = {EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE};
    target.eglImage = eglCreateImage(eglGetCurrentDisplay(), EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID,
                                     eglGetNativeClientBuffer(target.buffer), attributes);
    if (target.eglImage == EGL_NO_IMAGE_KHR) return false;

    GLint boundTexture = 0;
    GLint boundRead = 0;
    glGetIntegerv(GL_TEXTURE_BINDING_2D, &boundTexture);
    glGetIntegerv(GL_READ_FRAMEBUFFER_BINDING, &boundRead);
    glGenTextures(1, &target.texture);
    glBindTexture(GL_TEXTURE_2D, target.texture);
    glEGLImageTargetTexture2D(GL_TEXTURE_2D, target.eglImage);
    glGenFramebuffers(1, &target.framebuffer);
    glBindFramebuffer(GL_READ_FRAMEBUFFER, target.framebuffer);
    glFramebufferTexture2D(GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, target.texture, 0);
    bool complete = glCheckFramebufferStatus(GL_READ_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
    glBindFramebuffer(GL_READ_FRAMEBUFFER, (GLuint) boundRead);
    glBindTexture(GL_TEXTURE_2D, (GLuint) boundTexture);
    return complete && glGetError() == GL_NO_ERROR;
}

void VulkanBridge::dropTarget(bool glAlive) {
    if (glAlive) {
        if (target.framebuffer != 0) glDeleteFramebuffers(1, &target.framebuffer);
        if (target.texture != 0) glDeleteTextures(1, &target.texture);
    }
    if (target.eglImage != EGL_NO_IMAGE_KHR && eglDestroyImage != nullptr && glAlive) {
        eglDestroyImage(eglGetCurrentDisplay(), target.eglImage);
    }
    if (device != VK_NULL_HANDLE) {
        if (target.image != VK_NULL_HANDLE) vkDestroyImage(device, target.image, nullptr);
        if (target.memory != VK_NULL_HANDLE) vkFreeMemory(device, target.memory, nullptr);
    }
    if (target.buffer != nullptr) AHardwareBuffer_release(target.buffer);
    target = Target();
}

bool VulkanBridge::ensureTarget(unsigned width, unsigned height) {
    if (glSideStale) {
        // The old context's names went with it.
        target.framebuffer = 0;
        target.texture = 0;
        target.eglImage = EGL_NO_IMAGE_KHR;
        glSideStale = false;
        if (target.buffer != nullptr && !makeGlSide()) return fail("the frame's buffer couldn't be read by GL again");
    }
    if (target.buffer != nullptr && width <= target.width && height <= target.height) return true;

    // Grown to the largest frame yet: a frame never sizes it down.
    unsigned newWidth = std::max(width, target.width);
    unsigned newHeight = std::max(height, target.height);
    for (uint32_t slot = 0; slot < Handover::SLOTS; slot++) {
        if (!waitSlot(slot)) return false;
    }
    dropTarget(true);
    target.buffer = allocateBuffer(newWidth, newHeight);
    if (target.buffer == nullptr) return fail("no hardware buffer for the frame");
    target.width = newWidth;
    target.height = newHeight;
    if (!importBuffer(target.buffer, target.image, target.memory)) return fail("the frame's buffer couldn't be imported");
    if (!makeGlSide()) return fail("the frame's buffer couldn't be read by GL");
    LOGI("Vulkan: frames shared through a %u x %u buffer", newWidth, newHeight);
    return true;
}

bool VulkanBridge::waitSlot(uint32_t slot) {
    if (!handover.isPending(slot)) return true;
    VkResult result = vkWaitForFences(device, 1, &fences[slot], VK_TRUE, FENCE_TIMEOUT_NS);
    if (result == VK_ERROR_DEVICE_LOST) return fail("the device was lost");
    if (result != VK_SUCCESS) return fail("a frame didn't finish");
    vkResetFences(device, 1, &fences[slot]);
    handover.finished(slot);
    return true;
}

bool VulkanBridge::present(unsigned width, unsigned height, GLuint framebuffer, bool flip) {
    if (hasFailed() || !hasContext()) return false;
    if (forced == Forced::LOST && ++framesShown > FORCED_LOST_AFTER) return fail("the device was made to be lost");

    std::lock_guard<std::mutex> frameGuard(frameLock);
    Handover::Frame frame = handover.take();
    if (!waitSlot(frame.slot)) return false;
    bool copies = frame.image != nullptr && width > 0 && height > 0;
    if (copies && !ensureTarget(width, height)) return false;

    VkCommandBuffer command = commandBuffers[frame.slot];
    vkResetCommandBuffer(command, 0);
    VkCommandBufferBeginInfo begin {};
    begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vkBeginCommandBuffer(command, &begin);
    if (copies) {
        const VkImageViewCreateInfo &view = frame.image->create_info;
        VkImageSubresourceRange range {VK_IMAGE_ASPECT_COLOR_BIT, view.subresourceRange.baseMipLevel, 1, view.subresourceRange.baseArrayLayer, 1};
        VkImageLayout layout = frame.image->image_layout;
        bool general = layout == VK_IMAGE_LAYOUT_GENERAL;
        uint32_t family = frame.sourceFamily;
        bool transfers = family != VK_QUEUE_FAMILY_IGNORED && family != queueFamily;

        // The core's image, made ready to copy from (taken over from its queue family when another).
        std::array<VkImageMemoryBarrier, 2> before {};
        before[0].sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        before[0].srcAccessMask = VK_ACCESS_MEMORY_WRITE_BIT;
        before[0].dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
        before[0].oldLayout = layout;
        before[0].newLayout = general ? layout : VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
        before[0].srcQueueFamilyIndex = transfers ? family : VK_QUEUE_FAMILY_IGNORED;
        before[0].dstQueueFamilyIndex = transfers ? queueFamily : VK_QUEUE_FAMILY_IGNORED;
        before[0].image = view.image;
        before[0].subresourceRange = range;
        // The shared buffer, taken back from GL; what it held is drawn over.
        before[1].sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        before[1].srcAccessMask = 0;
        before[1].dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        before[1].oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        before[1].newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
        before[1].srcQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT;
        before[1].dstQueueFamilyIndex = queueFamily;
        before[1].image = target.image;
        before[1].subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0,
                             0, nullptr, 0, nullptr, (uint32_t) before.size(), before.data());

        VkImageLayout source = general ? layout : VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
        VkImageSubresourceLayers from {VK_IMAGE_ASPECT_COLOR_BIT, range.baseMipLevel, range.baseArrayLayer, 1};
        VkImageSubresourceLayers to {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
        if (copiesAsIs(view.format)) {
            VkImageCopy region {from, {0, 0, 0}, to, {0, 0, 0}, {width, height, 1}};
            vkCmdCopyImage(command, view.image, source, target.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &region);
        } else {
            VkImageBlit region {};
            region.srcSubresource = from;
            region.srcOffsets[1] = {(int32_t) width, (int32_t) height, 1};
            region.dstSubresource = to;
            region.dstOffsets[1] = {(int32_t) width, (int32_t) height, 1};
            vkCmdBlitImage(command, view.image, source, target.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &region, VK_FILTER_NEAREST);
        }

        // The core's image back as it was (and to its family); the buffer handed to GL.
        std::array<VkImageMemoryBarrier, 2> after {};
        after[0].sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        after[0].srcAccessMask = 0;
        after[0].dstAccessMask = VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT;
        after[0].oldLayout = source;
        after[0].newLayout = layout;
        after[0].srcQueueFamilyIndex = transfers ? queueFamily : VK_QUEUE_FAMILY_IGNORED;
        after[0].dstQueueFamilyIndex = transfers ? family : VK_QUEUE_FAMILY_IGNORED;
        after[0].image = view.image;
        after[0].subresourceRange = range;
        after[1].sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        after[1].srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        after[1].dstAccessMask = 0;
        after[1].oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
        after[1].newLayout = VK_IMAGE_LAYOUT_GENERAL;
        after[1].srcQueueFamilyIndex = queueFamily;
        after[1].dstQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT;
        after[1].image = target.image;
        after[1].subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0,
                             0, nullptr, 0, nullptr, (uint32_t) after.size(), after.data());
    }
    vkEndCommandBuffer(command);

    // The core's command buffers first, then the copy; its semaphores waited on, its signal raised.
    std::vector<VkCommandBuffer> commands = frame.commands;
    commands.push_back(command);
    std::vector<VkPipelineStageFlags> stages(frame.waits.size(), VK_PIPELINE_STAGE_ALL_COMMANDS_BIT);
    VkSubmitInfo submit {};
    submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submit.waitSemaphoreCount = (uint32_t) frame.waits.size();
    submit.pWaitSemaphores = frame.waits.data();
    submit.pWaitDstStageMask = stages.data();
    submit.commandBufferCount = (uint32_t) commands.size();
    submit.pCommandBuffers = commands.data();
    submit.signalSemaphoreCount = frame.hasSignal ? 1 : 0;
    submit.pSignalSemaphores = &frame.signal;
    VkResult result;
    {
        // The core submits from its own threads, under this same lock (lock_queue).
        std::lock_guard<std::recursive_mutex> lock(queueLock);
        result = vkQueueSubmit(queue, 1, &submit, fences[frame.slot]);
    }
    if (result == VK_ERROR_DEVICE_LOST) return fail("the device was lost");
    if (result != VK_SUCCESS) return fail("the frame couldn't be submitted");
    handover.submitted();

    // GL reads the buffer once the copy is done.
    if (!waitSlot(frame.slot)) return false;

    GLint boundRead = 0;
    GLint boundDraw = 0;
    glGetIntegerv(GL_READ_FRAMEBUFFER_BINDING, &boundRead);
    glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, &boundDraw);
    GLboolean scissor = glIsEnabled(GL_SCISSOR_TEST);
    if (scissor) glDisable(GL_SCISSOR_TEST);
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, framebuffer);
    if (copies) {
        glBindFramebuffer(GL_READ_FRAMEBUFFER, target.framebuffer);
        GLint top = (GLint) height;
        glBlitFramebuffer(0, 0, (GLint) width, top, 0, flip ? top : 0, (GLint) width, flip ? 0 : top, GL_COLOR_BUFFER_BIT, GL_NEAREST);
    } else {
        // The core's blanking: a black frame.
        GLfloat clear[4];
        glGetFloatv(GL_COLOR_CLEAR_VALUE, clear);
        glClearColor(0, 0, 0, 1);
        glClear(GL_COLOR_BUFFER_BIT);
        glClearColor(clear[0], clear[1], clear[2], clear[3]);
    }
    if (scissor) glEnable(GL_SCISSOR_TEST);
    glBindFramebuffer(GL_READ_FRAMEBUFFER, (GLuint) boundRead);
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, (GLuint) boundDraw);
    return true;
}

void VulkanBridge::onGlContextCreated() {
    glSideStale = true;
}

void VulkanBridge::destroy() {
    if (device != VK_NULL_HANDLE) {
        vkDeviceWaitIdle(device);
        for (auto &fence : fences) {
            if (fence != VK_NULL_HANDLE) vkDestroyFence(device, fence, nullptr);
            fence = VK_NULL_HANDLE;
        }
        if (commandPool != VK_NULL_HANDLE) vkDestroyCommandPool(device, commandPool, nullptr);
        commandPool = VK_NULL_HANDLE;
        // Called off the GL thread, as the player closes: the GL names go with the process.
        dropTarget(false);
        if (negotiation != nullptr && negotiation->destroy_device != nullptr) negotiation->destroy_device();
        vkDestroyDevice(device, nullptr);
    }
    if (instance != VK_NULL_HANDLE && vkDestroyInstance != nullptr) vkDestroyInstance(instance, nullptr);
    device = VK_NULL_HANDLE;
    instance = VK_NULL_HANDLE;
    gpu = VK_NULL_HANDLE;
    queue = VK_NULL_HANDLE;
    negotiation = nullptr;
    coreMadeDevice = false;
    accepted = false;
    offered = false;
    forced = Forced::NONE;
    probed = -1;
    failure.clear();
    framesShown = 0;
    glSideStale = false;
    handover.reset();
    vulkanInterface = {};
}

void VulkanBridge::setImage(void *handle, const retro_vulkan_image *image, uint32_t count, const VkSemaphore *semaphores, uint32_t family) {
    auto *bridge = static_cast<VulkanBridge*>(handle);
    std::lock_guard<std::mutex> guard(bridge->frameLock);
    bridge->handover.setImage(image, std::vector<VkSemaphore>(semaphores, semaphores + count), family);
}

uint32_t VulkanBridge::getSyncIndex(void *handle) {
    auto *bridge = static_cast<VulkanBridge*>(handle);
    std::lock_guard<std::mutex> guard(bridge->frameLock);
    return bridge->handover.syncIndex();
}

uint32_t VulkanBridge::getSyncIndexMask(void *handle) {
    return Handover::syncMask();
}

void VulkanBridge::setCommandBuffers(void *handle, uint32_t count, const VkCommandBuffer *commands) {
    auto *bridge = static_cast<VulkanBridge*>(handle);
    std::lock_guard<std::mutex> guard(bridge->frameLock);
    bridge->handover.setCommands(std::vector<VkCommandBuffer>(commands, commands + count));
}

void VulkanBridge::waitSyncIndex(void *handle) {
    auto *bridge = static_cast<VulkanBridge*>(handle);
    std::lock_guard<std::mutex> guard(bridge->frameLock);
    bridge->waitSlot(bridge->handover.syncIndex());
}

void VulkanBridge::lockQueue(void *handle) {
    static_cast<VulkanBridge*>(handle)->queueLock.lock();
}

void VulkanBridge::unlockQueue(void *handle) {
    static_cast<VulkanBridge*>(handle)->queueLock.unlock();
}

void VulkanBridge::setSignalSemaphore(void *handle, VkSemaphore semaphore) {
    auto *bridge = static_cast<VulkanBridge*>(handle);
    std::lock_guard<std::mutex> guard(bridge->frameLock);
    bridge->handover.setSignal(semaphore);
}

}
