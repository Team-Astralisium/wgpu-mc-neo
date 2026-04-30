package dev.birb.wgpu.backend

import com.mojang.blaze3d.pipeline.CompiledRenderPipeline

class WgpuCompiledRenderPipeline : CompiledRenderPipeline {
    override fun isValid(): Boolean = true
}
