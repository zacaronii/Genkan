#ifndef LIBRETRODROID_VULKANHANDOVER_H
#define LIBRETRODROID_VULKANHANDOVER_H

#include <array>
#include <cstdint>
#include <vector>

namespace libretrodroid {

/**
 * Genkan: the frontend's side of libretro's Vulkan handover (libretro_vulkan.h), as bookkeeping
 * without Vulkan, so a host test can run it. What the core hands over before each video refresh
 * (set_image, set_command_buffers, set_signal_semaphore) is used once by the frame that follows:
 *
 * - The semaphores given with an image are waited on once; a refresh without a new image waits
 *   on none. Command buffers given for a frame are submitted once, ahead of the frontend's, and
 *   then no semaphore is waited on (the core synchronises with barriers instead).
 * - The signal semaphore goes with the next refresh, a duped one too, and is then spent.
 * - Each frame's work belongs to a sync slot: [SLOTS] of them, taken in turn, so the core can
 *   reuse what it gave a slot once the frontend's work for it is done (wait_sync_index).
 *
 * [Image], [Semaphore] and [Command] are the Vulkan types in the player, plain values in the test.
 */
template <typename Image, typename Semaphore, typename Command>
class VulkanHandover {
public:
    static constexpr uint32_t SLOTS = 2;

    struct Frame {
        const Image *image = nullptr;
        uint32_t sourceFamily = 0;
        std::vector<Semaphore> waits;
        std::vector<Command> commands;
        bool hasSignal = false;
        Semaphore signal {};
        uint32_t slot = 0;
    };

    /** The slot this frame's work goes to (get_sync_index). */
    uint32_t syncIndex() const { return slot; }

    /** Every slot get_sync_index can return, as a bit each (get_sync_index_mask). */
    static constexpr uint32_t syncMask() { return (1u << SLOTS) - 1; }

    /** set_image: [image] null clears it (the core's blanking: a black frame). */
    void setImage(const Image *image, std::vector<Semaphore> semaphores, uint32_t family) {
        this->image = image;
        this->waits = std::move(semaphores);
        this->sourceFamily = family;
    }

    void setCommands(std::vector<Command> commands) { this->commands = std::move(commands); }

    void setSignal(Semaphore semaphore) {
        signal = semaphore;
        hasSignal = true;
    }

    /** What the frame being shown uses, in the current slot; what's used once is spent. */
    Frame take() {
        Frame frame;
        frame.image = image;
        frame.sourceFamily = sourceFamily;
        frame.commands = std::move(commands);
        commands.clear();
        if (frame.commands.empty()) frame.waits = std::move(waits);
        waits.clear();
        frame.hasSignal = hasSignal;
        frame.signal = signal;
        hasSignal = false;
        signal = Semaphore {};
        frame.slot = slot;
        return frame;
    }

    /** The current slot's work was submitted: the next frame takes the next slot. */
    void submitted() {
        pending[slot] = true;
        slot = (slot + 1) % SLOTS;
    }

    /** Whether [slot] has work the frontend submitted and hasn't seen finish. */
    bool isPending(uint32_t at) const { return pending[at]; }

    void finished(uint32_t at) { pending[at] = false; }

    void reset() { *this = VulkanHandover(); }

private:
    const Image *image = nullptr;
    uint32_t sourceFamily = 0;
    std::vector<Semaphore> waits;
    std::vector<Command> commands;
    bool hasSignal = false;
    Semaphore signal {};
    uint32_t slot = 0;
    std::array<bool, SLOTS> pending {};
};

}

#endif //LIBRETRODROID_VULKANHANDOVER_H
