// Genkan: the frontend's side of libretro's Vulkan handover (vulkanhandover.h), on plain values.
#include <cstdio>

#include "vulkanhandover.h"

namespace {

int failures = 0;

void check(bool ok, const char *what) {
    if (!ok) {
        std::fprintf(stderr, "FAIL: %s\n", what);
        failures++;
    }
}

// A made-up image, semaphores and command buffers: numbers stand for the Vulkan handles.
struct Image { int id; };
using Handover = libretrodroid::VulkanHandover<Image, int, int>;

}

int main() {
    Handover handover;
    check(Handover::syncMask() == 0b11, "two sync slots, each a bit of the mask");
    check(handover.syncIndex() == 0, "the first frame's work goes to slot 0");

    Image picture {7};
    handover.setImage(&picture, {11, 12}, 3);
    auto first = handover.take();
    check(first.image == &picture && first.sourceFamily == 3, "the image set is the one shown");
    check(first.waits.size() == 2 && first.waits[0] == 11, "its semaphores are waited on");
    check(first.slot == 0 && !first.hasSignal && first.commands.empty(), "in slot 0, with nothing else");
    handover.submitted();
    check(handover.syncIndex() == 1 && handover.isPending(0), "the next frame takes slot 1, slot 0 still at work");

    auto duped = handover.take();
    check(duped.image == &picture && duped.waits.empty(), "a refresh without a new image shows it again and waits on nothing");
    handover.submitted();
    check(handover.syncIndex() == 0, "and the slots go round");
    handover.finished(0);
    check(!handover.isPending(0) && handover.isPending(1), "slot 0 done, slot 1 still at work");

    handover.setImage(&picture, {21}, 3);
    handover.setCommands({31, 32});
    handover.setSignal(41);
    auto commands = handover.take();
    check(commands.commands.size() == 2 && commands.commands[1] == 32, "command buffers given go ahead of the frontend's");
    check(commands.waits.empty(), "and then no semaphore is waited on");
    check(commands.hasSignal && commands.signal == 41, "the signal semaphore goes with this refresh");
    auto after = handover.take();
    check(after.commands.empty() && !after.hasSignal && after.waits.empty(), "each is used once");

    handover.setImage(nullptr, {}, 3);
    check(handover.take().image == nullptr, "a cleared image is a black frame");

    handover.reset();
    check(handover.syncIndex() == 0 && !handover.isPending(0) && !handover.isPending(1) && handover.take().image == nullptr,
          "a reset forgets it all");

    if (failures == 0) std::printf("vulkan handover: passed\n");
    return failures == 0 ? 0 : 1;
}
