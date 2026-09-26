package dev.birb.wgpu.backend

import dev.birb.wgpu.WgpuMcMod
import net.minecraft.client.renderer.RenderPipelines
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Compiles Minecraft''s pipelines before the world asks for them.
 *
 * Two things happen the first time a pipeline is bound: its shaders are translated - cyntax
 * preprocesses them, the AST is rewritten, naga is run over the result to find the uniform block
 * sizes - and the driver builds a graphics pipeline. Neither belongs on the render thread in the
 * middle of a frame, and neither has to be: the set of pipelines is known ahead of time
 * ([RenderPipelines.getStaticPipelines]), and the loading screen is a window with nothing else to do
 * but this.
 *
 * The translation half is also cached across launches by the native side, so on every run after the
 * first this pass is mostly a few hundred file reads plus the driver''s own pipeline cache.
 *
 * Runs on one low-priority daemon thread, and only one pass at a time: a resource reload can start
 * while the previous pass is still going, and the older pass stops as soon as it notices
 * ([generation]). Pipelines the pass compiled are not thrown away by that - they live in
 * `WgpuCompiledRenderPipeline`''s cache until a reload clears it.
 */
object PipelinePrecompiler {

	private val executor = Executors.newSingleThreadExecutor { runnable ->
		Thread(runnable, "wgpu-mc pipeline precompile").apply {
			isDaemon = true
			priority = Thread.MIN_PRIORITY
		}
	}

	/** Bumped by every pass, so an older one can tell that it has been replaced. */
	private val generation = AtomicInteger()

	/** Whether a pass has ever been started, so the first one can say how long it took. */
	@Volatile
	private var passes = 0

	/**
	 * Compiles every static pipeline in the background, if the renderer is up.
	 *
	 * Called after a resource reload, which is where the shaders the pipelines name have just been
	 * read and where the renderer''s own pipeline cache has just been cleared - so this is both the
	 * earliest moment the sources exist and the only moment they need compiling again.
	 */
	@JvmStatic
	fun precompile() {
		val device = dev.birb.wgpu.render.Wgpu.device() ?: return

		if (!dev.birb.wgpu.render.Wgpu.isRendererLive()) {
			return
		}

		val mine = generation.incrementAndGet()
		val pipelines = try {
			RenderPipelines.getStaticPipelines()
		} catch (error: Throwable) {
			WgpuMcMod.LOGGER.warn("wgpu: could not list Minecraft's pipelines to precompile them", error)
			return
		}

		executor.execute {
			val started = System.nanoTime()
			var compiled = 0
			var failed = 0

			for (pipeline in pipelines) {
				if (generation.get() != mine) {
					// A newer reload replaced this pass; its pipelines are the ones worth compiling.
					return@execute
				}

				try {
					// Both depth variants: which one a pass binds depends on that pass having a depth
					// attachment, and Minecraft uses both for the same pipeline - the same terrain
					// pipeline draws into the main target and into the shadow pass.
					WgpuCompiledRenderPipeline
						.of(device, pipeline, device.defaultShaderSource, false)
						.forDepth(true)
					compiled++
				} catch (error: Throwable) {
					// A pipeline that will not compile is one Minecraft would have failed on anyway,
					// at the first draw - and there it would be a crash inside a pass. Saying so now
					// keeps the failure where it can be read, and the rest of the pass still runs.
					failed++
					WgpuMcMod.LOGGER.warn(
						"wgpu: precompiling {} failed; it will fail again when a pass binds it",
						pipeline.location,
						error,
					)
				}
			}

			val elapsed = (System.nanoTime() - started) / 1_000_000

			if (generation.get() != mine) {
				return@execute
			}

			passes++
			WgpuMcMod.LOGGER.info(
				"wgpu: precompiled {} pipeline(s) in {}ms{}",
				compiled,
				elapsed,
				if (failed == 0) "" else " ($failed failed)",
			)
		}
	}

	/** How many passes have finished, for the diagnostics that ask. */
	@JvmStatic
	fun passes(): Int = passes
}