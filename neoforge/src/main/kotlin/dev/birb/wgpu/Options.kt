package dev.birb.wgpu

enum class Backend {
    Vulkan,
    DirectX12,
    DirectX11,
    Metal,
    Opengl
}

object Options {
    var BACKEND: Backend = Backend.Vulkan
    var HDR: Boolean = false
}
