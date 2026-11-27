#ifndef LIBRETRODROID_VULKANBRIDGE_H
#define LIBRETRODROID_VULKANBRIDGE_H

#define VK_USE_PLATFORM_ANDROID_KHR
#define VK_NO_PROTOTYPES
#include <vulkan/vulkan.h>
#include <libretro_vulkan.h>

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <android/hardware_buffer.h>

#include <array>
#include <mutex>
#include <string>

#include "vulkanhandover.h"

namespace libretrodroid {

/**
 * Genkan: a Vulkan context for a core that draws with Vulkan (LRPS2), shown through libretrodroid's
 * GL path. Only a core and ladder step that ask for it are offered one ([offer]); every other core
 * is answered as before, and is never offered Vulkan.
 *
 * The core makes the device itself (its context negotiation, create_device2) with the extensions
 * this needs added. Each frame its image is copied on the GPU into an AHardwareBuffer, which GL
 * reads as an EGLImage and copies into the core's framebuffer of the GL path, so what reads that
 * framebuffer (the looks, snapshots and frame grabs, the redraw of a held step) works as for a GL
 * core.
 *
 * A device that can't do this is found before the game loads ([probe], a throwaway one that imports
 * a buffer), and the core then gets GL in the same load. A failure after the load (the core's own
 * device, the buffer, a lost device) is kept in [failure]: the player starts the game again on GL.
 */
class VulkanBridge {
public:
    /** A failure to force, to see the way back to GL on a device (LibretroDroid.offerVulkan). */
    enum class Forced { NONE = 0, PROBE = 1, DEVICE = 2, LOST = 3 };

    static VulkanBridge& getInstance() {
        static VulkanBridge instance;
        return instance;
    }

    VulkanBridge(VulkanBridge const&) = delete;
    void operator=(VulkanBridge const&) = delete;

    /** Before the game loads: whether the core may have Vulkan, and a failure to force. */
    void offer(bool offered, Forced forced);
    bool isOffered() const { return offered; }

    /** Whether this device can do what the bridge needs; tried once, at the core's first ask. */
    bool probe();

    /** The core was given a Vulkan context (SET_HW_RENDER). */
    void accept() { accepted = true; }
    bool isAccepted() const { return accepted; }

    void setNegotiation(const retro_hw_render_context_negotiation_interface_vulkan *negotiation);

    /** Before the core's context_reset, on the GL thread: the instance and device, once. False: see [failure]. */
    bool createContext();
    bool hasContext() const { return device != VK_NULL_HANDLE; }

    /** What the core reads at GET_HW_RENDER_INTERFACE. */
    const retro_hw_render_interface_vulkan *interface() const { return hasContext() ? &vulkanInterface : nullptr; }

    /**
     * A frame at the video refresh: the core's image, [width] x [height], copied into GL
     * framebuffer [framebuffer], upside down when [flip] (a core whose frames start at the bottom).
     * False when it failed (see [failure]).
     */
    bool present(unsigned width, unsigned height, GLuint framebuffer, bool flip);

    /** A new GL context: the GL side of the buffer is made again at the next frame. */
    void onGlContextCreated();

    /** After the core is unloaded: everything, the device last. */
    void destroy();

    bool hasFailed() const { return !failure.empty(); }
    const std::string& failureReason() const { return failure; }

private:
    VulkanBridge() = default;

    struct Target {
        AHardwareBuffer *buffer = nullptr;
        unsigned width = 0;
        unsigned height = 0;
        VkImage image = VK_NULL_HANDLE;
        VkDeviceMemory memory = VK_NULL_HANDLE;
        EGLImageKHR eglImage = EGL_NO_IMAGE_KHR;
        GLuint texture = 0;
        GLuint framebuffer = 0;
    };

    using Handover = VulkanHandover<retro_vulkan_image, VkSemaphore, VkCommandBuffer>;

    bool loadLoader();
    bool loadDevice();
    bool makeInstance(const VkApplicationInfo *app);
    bool pickGpu();
    bool deviceHasExtensions(VkPhysicalDevice gpu, std::string &missing);
    VkDevice makeDevice(VkPhysicalDevice gpu, const VkDeviceCreateInfo *info);
    bool makeOwnDevice();
    bool makeFrameObjects();
    bool importBuffer(AHardwareBuffer *buffer, VkImage &image, VkDeviceMemory &memory);
    bool ensureTarget(unsigned width, unsigned height);
    bool makeGlSide();
    void dropTarget(bool glAlive);
    bool waitSlot(uint32_t slot);
    bool fail(const std::string &why);
    void destroyProbe();

    static VkDevice VKAPI_CALL createDeviceWrapper(VkPhysicalDevice gpu, void *opaque, const VkDeviceCreateInfo *info);
    static VkInstance VKAPI_CALL createInstanceWrapper(void *opaque, const VkInstanceCreateInfo *info);
    static void setImage(void *handle, const retro_vulkan_image *image, uint32_t count, const VkSemaphore *semaphores, uint32_t family);
    static uint32_t getSyncIndex(void *handle);
    static uint32_t getSyncIndexMask(void *handle);
    static void setCommandBuffers(void *handle, uint32_t count, const VkCommandBuffer *commands);
    static void waitSyncIndex(void *handle);
    static void lockQueue(void *handle);
    static void unlockQueue(void *handle);
    static void setSignalSemaphore(void *handle, VkSemaphore semaphore);

    bool offered = false;
    Forced forced = Forced::NONE;
    int probed = -1;
    bool accepted = false;
    std::string failure;

    const retro_hw_render_context_negotiation_interface_vulkan *negotiation = nullptr;

    void *loader = nullptr;
    PFN_vkGetInstanceProcAddr getInstanceProcAddr = nullptr;
    VkInstance instance = VK_NULL_HANDLE;
    VkPhysicalDevice gpu = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkQueue queue = VK_NULL_HANDLE;
    uint32_t queueFamily = 0;
    bool coreMadeDevice = false;

    VkCommandPool commandPool = VK_NULL_HANDLE;
    std::array<VkCommandBuffer, Handover::SLOTS> commandBuffers {};
    std::array<VkFence, Handover::SLOTS> fences {};

    Target target;
    bool glSideStale = false;
    unsigned framesShown = 0;

    Handover handover;
    // The handover and the fences: the core may hand over or wait from a thread of its own. Taken
    // before queueLock, never after; the core's own submits take only queueLock.
    std::mutex frameLock;
    std::recursive_mutex queueLock;
    retro_hw_render_interface_vulkan vulkanInterface {};

    // The loader's and the device's entry points this uses.
    PFN_vkCreateInstance vkCreateInstance = nullptr;
    PFN_vkEnumerateInstanceVersion vkEnumerateInstanceVersion = nullptr;
    PFN_vkDestroyInstance vkDestroyInstance = nullptr;
    PFN_vkEnumeratePhysicalDevices vkEnumeratePhysicalDevices = nullptr;
    PFN_vkGetPhysicalDeviceProperties vkGetPhysicalDeviceProperties = nullptr;
    PFN_vkGetPhysicalDeviceQueueFamilyProperties vkGetPhysicalDeviceQueueFamilyProperties = nullptr;
    PFN_vkEnumerateDeviceExtensionProperties vkEnumerateDeviceExtensionProperties = nullptr;
    PFN_vkCreateDevice vkCreateDevice = nullptr;
    PFN_vkGetDeviceProcAddr vkGetDeviceProcAddr = nullptr;
    PFN_vkDestroyDevice vkDestroyDevice = nullptr;
    PFN_vkGetDeviceQueue vkGetDeviceQueue = nullptr;
    PFN_vkDeviceWaitIdle vkDeviceWaitIdle = nullptr;
    PFN_vkCreateCommandPool vkCreateCommandPool = nullptr;
    PFN_vkDestroyCommandPool vkDestroyCommandPool = nullptr;
    PFN_vkAllocateCommandBuffers vkAllocateCommandBuffers = nullptr;
    PFN_vkBeginCommandBuffer vkBeginCommandBuffer = nullptr;
    PFN_vkEndCommandBuffer vkEndCommandBuffer = nullptr;
    PFN_vkResetCommandBuffer vkResetCommandBuffer = nullptr;
    PFN_vkCmdPipelineBarrier vkCmdPipelineBarrier = nullptr;
    PFN_vkCmdCopyImage vkCmdCopyImage = nullptr;
    PFN_vkCmdBlitImage vkCmdBlitImage = nullptr;
    PFN_vkQueueSubmit vkQueueSubmit = nullptr;
    PFN_vkCreateFence vkCreateFence = nullptr;
    PFN_vkDestroyFence vkDestroyFence = nullptr;
    PFN_vkWaitForFences vkWaitForFences = nullptr;
    PFN_vkResetFences vkResetFences = nullptr;
    PFN_vkCreateImage vkCreateImage = nullptr;
    PFN_vkDestroyImage vkDestroyImage = nullptr;
    PFN_vkAllocateMemory vkAllocateMemory = nullptr;
    PFN_vkFreeMemory vkFreeMemory = nullptr;
    PFN_vkBindImageMemory vkBindImageMemory = nullptr;
    PFN_vkGetAndroidHardwareBufferPropertiesANDROID vkGetAndroidHardwareBufferPropertiesANDROID = nullptr;

    PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC eglGetNativeClientBuffer = nullptr;
    PFNEGLCREATEIMAGEKHRPROC eglCreateImage = nullptr;
    PFNEGLDESTROYIMAGEKHRPROC eglDestroyImage = nullptr;
    void (*glEGLImageTargetTexture2D)(GLenum, void *) = nullptr;
};

}

#endif //LIBRETRODROID_VULKANBRIDGE_H
