package dev.birb.wgpu.render

import dev.birb.wgpu.WgpuMcMod
import dev.birb.wgpu.chunk.RustChunkBake
import dev.birb.wgpu.rust.WmNative
import dev.birb.wgpu.rust.WgpuNative
import net.minecraft.client.Minecraft
import net.minecraft.core.SectionPos
import org.joml.Matrix4f
import java.lang.foreign.MemorySegment

/**
 * The Rust terrain pass, from this side: which of Minecraft's passes it stands in for, and the camera
 * it is drawn with.
 *
 * The graph pass draws the sections the Rust baker meshed (see [RustChunkBake]), and it draws them
 * *instead of* Minecraft's own solid layer rather than beside it: two sets of the same terrain are two
 * sets of the same triangles, and the pass that draws it has to be the one the rest of the frame shares
 * its depth buffer with - which is why the takeover happens where Minecraft's own pass would have
 * been opened. So the two things this side has to get right are which pass (see [replaces]) and with
 * what camera (see [sendCameraMatrices]).
 */
object TerrainPass {
	/**
	 * The pipeline whose pass the graph draws instead.
	 *
	 * The solid layer only: the baker meshes that layer, and the cutout and translucent layers stay
	 * Minecraft's - drawn into the depth buffer this pass fills, which is the whole point of drawing it
	 * in the same pass Minecraft would have.
	 */
	private const val SOLID_TERRAIN = "minecraft:pipeline/solid_terrain"

	/** Whether the path is switched on at all. See [RustChunkBake.MARKER]. */
	fun isOn(): Boolean = RustChunkBake.isOn()

	/** Whether the graph draws the pass a pipeline belongs to. */
	fun replaces(location: String?): Boolean = location == SOLID_TERRAIN

	/**
	 * Whether the graph can draw it yet.
	 *
	 * The graph's terrain pipeline is built from the block atlas, which a resource reload stitches on a
	 * background thread, so for the first seconds of a session there is nothing to draw with. A pass
	 * taken away from Minecraft then is a frame with no ground in it rather than a frame drawn another
	 * way, so the question is asked first - and the answer is kept, because a pipeline does not unbuild
	 * itself and this is a native call per frame otherwise.
	 */
	fun ready(renderer: MemorySegment): Boolean {
		if (canDraw) {
			return true
		}

		canDraw = WmNative.terrainPassReady.invokeExact(renderer) as Boolean
		if (canDraw) {
			WgpuMcMod.LOGGER.info(
				"wgpu: the render graph is drawing the solid terrain; Minecraft's own meshes for that layer are skipped"
			)
		}

		return canDraw
	}

	@Volatile
	private var canDraw = false

	private val projection = FloatArray(16)
	private val model = FloatArray(16)

	/**
	 * The view matrix the pass is drawn with, which is the identity: see [sendCameraMatrices].
	 *
	 * Column-major, as every matrix here is - `Matrix4f.get` writes that order and WGSL reads it.
	 */
	private val identity = floatArrayOf(
		1f, 0f, 0f, 0f,
		0f, 1f, 0f, 0f,
		0f, 0f, 1f, 0f,
		0f, 0f, 0f, 1f,
	)

	/**
	 * Sends the three matrices the graph's terrain pipeline binds.
	 *
	 * The projection is the camera's view rotation *and* projection already multiplied, because that is
	 * the matrix Minecraft's camera offers: the shader multiplies a section by the projection, then the
	 * view, then the model, so the view it is given is the identity and the rotation rides in the
	 * projection.
	 *
	 * The model matrix is what makes the shader's own arithmetic camera-relative. It places a section
	 * at `section * 16` relative to the camera's *section*, and the difference between that and where
	 * the camera actually is, is what this translates by. Y is not taken relative to a section - the
	 * graph works in absolute section heights - so the camera's own height is in the matrix as it is.
	 */
	fun sendCameraMatrices() {
		val client = Minecraft.getInstance() ?: return
		val camera = client.gameRenderer.mainCamera ?: return

		camera.getViewRotationProjectionMatrix(Matrix4f()).get(projection)

		val position = camera.position()
		val sectionX = SectionPos.blockToSectionCoord(position.x)
		val sectionZ = SectionPos.blockToSectionCoord(position.z)

		Matrix4f()
			.translation(
				-(position.x - sectionX * 16.0).toFloat(),
				-position.y.toFloat(),
				-(position.z - sectionZ * 16.0).toFloat(),
			)
			.get(model)

		WgpuNative.setMatrix(MATRIX_PROJECTION, projection)
		WgpuNative.setMatrix(MATRIX_VIEW, identity)
		WgpuNative.setMatrix(MATRIX_MODEL, model)
	}

	/** The ids `set_matrix` in `renderer.rs` reads: 0 = projection, 2 = view, 3 = model. */
	private const val MATRIX_PROJECTION = 0
	private const val MATRIX_VIEW = 2
	private const val MATRIX_MODEL = 3
}