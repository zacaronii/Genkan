/*
 *     Copyright (C) 2019  Filippo Scognamiglio
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

#include "framebufferrenderer.h"

#include <algorithm>
#include "../framefill.h"
#include "es3utils.h"
#include "../../log.h"

namespace libretrodroid {

FramebufferRenderer::FramebufferRenderer(
    unsigned width,
    unsigned height,
    bool depth,
    bool stencil,
    ShaderManager::Chain shaders
) {
    this->depth = depth;
    this->stencil = stencil;
    this->width = width;
    this->height = height;
    this->askedWidth = width;
    this->askedHeight = height;
    this->shaders = std::move(shaders);

    initializeBuffers();
}

void FramebufferRenderer::onNewFrame(const void *data, unsigned width, unsigned height, size_t pitch) {
    Renderer::onNewFrame(data, width, height, pitch);

    // Genkan: a frame larger than the framebuffer grows it (framefill.h's framebufferSize).
    if ((int) width > largestWidth || (int) height > largestHeight) {
        largestWidth = std::max(largestWidth, (int) width);
        largestHeight = std::max(largestHeight, (int) height);
        resize();
    }

    if (isDirty) {
        initializeBuffers();
        isDirty = false;
    }
}

void FramebufferRenderer::initializeBuffers() {
    framebuffers = ES3Utils::buildShaderPasses(width, height, shaders);

    ES3Utils::deleteFramebuffer(std::move(framebuffer));
    framebuffer = ES3Utils::createFramebuffer(
        width,
        height,
        shaders.linearTexture,
        false,
        depth,
        stencil
    );
}

uintptr_t FramebufferRenderer::getTexture() {
    return framebuffer->texture;
}

uintptr_t FramebufferRenderer::getFramebuffer() {
    return framebuffer->framebuffer;
}

void FramebufferRenderer::setPixelFormat(int pixelFormat) {
    // TODO... Here we should handle 32bit framebuffers.
}

void FramebufferRenderer::updateRenderedResolution(unsigned int width, unsigned int height) {
    askedWidth = width;
    askedHeight = height;
    resize();
}

void FramebufferRenderer::resize() {
    auto size = framebufferSize(askedWidth, askedHeight, largestWidth, largestHeight);
    if (this->width != size.first || this->height != size.second) {
        LOGI("Framebuffer for the core: %u x %u", size.first, size.second);
        this->width = size.first;
        this->height = size.second;
        isDirty = true;
    }
}

std::pair<float, float> FramebufferRenderer::filledFraction() {
    return libretrodroid::filledFraction(lastFrameSize.first, lastFrameSize.second, width, height);
}

bool FramebufferRenderer::rendersInVideoCallback() {
    return true;
}

void FramebufferRenderer::setShaders(ShaderManager::Chain shaders) {
    if (shaders != this->shaders) {
        this->shaders = shaders;
        isDirty = true;
    }
}

Renderer::PassData FramebufferRenderer::getPassData(unsigned int layer) {
    PassData result;

    if (layer >= 0 && layer < framebuffers->size()) {
        result.framebuffer = framebuffers->at(layer)->framebuffer;
        result.width = framebuffers->at(layer)->width;
        result.height = framebuffers->at(layer)->height;
    }

    if (layer > 0 && layer < framebuffers->size() + 1) {
        result.texture = framebuffers->at(layer - 1)->texture;
    }

    return result;
}

} //namespace libretrodroid
