package dev.birb.wgpu.backend;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassBackend;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.birb.wgpu.rust.NativeNames;
import dev.birb.wgpu.rust.WmNative;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import org.jspecify.annotations.NonNull;

import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Blaze3D's render pass, forwarding to a {@code wgpu::RenderPass}.
 *
 * <p>This one class is deliberately written in Java rather than Kotlin. Kotlin cannot override an
 * interface method declared with an unbounded type parameter, and {@code RenderPassBackend}
 * declares {@code <T> void drawMultipleIndexed(...)}; implementing the interface in Java is the
 * only way to satisfy it. The rest of the backend stays in Kotlin.
 *
 * <p>The interesting part of the port is the descriptor marshalling: {@code
 * BlazeRenderPassDescriptor} is a struct-of-structs in the C ABI, so the whole thing is built
 * inside one confined {@link Arena} whose lifetime is exactly the native call. Field offsets come
 * from {@link WmNative} so they can be checked against {@code bindings.h}.
 *
 * <p>Bindings are written into a {@code DrawCall} as they arrive, and a draw is one call across the
 * ABI carrying the pipeline, the buffers, the parameters and every binding by slot - see {@link #record}.
 * The bind groups themselves live in the native pass and are reused between draws.
 */
public class WgpuRenderPass implements RenderPassBackend {

    private static final MemoryLayout CLEAR_COLOR_LAYOUT =
            MemoryLayout.sequenceLayout(4, ValueLayout.JAVA_FLOAT);

    private final WgpuDevice device;
    private final WgpuCommandEncoder encoder;
    private final MemorySegment nativePass;
    private final boolean wantsDepth;
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * The scratch buffer every draw of this pass is written into, taken from a per-thread pool.
     *
     * <p>A pass is created and closed many times per frame and the buffer is the same 1.2 KiB every
     * time, so an arena per pass paid for an arena, an allocation and a close on the draw path for
     * memory that was handed straight back. A closed pass returns its buffer to its thread's pool
     * and the next pass takes it back: the arena is created once per render thread and the buffer
     * once per pass that is open at the same time - nested passes included, since each takes its own.
     *
     * <p>A reused buffer is reset as it is taken, because the pass before it left its bindings, its
     * vertex buffer mask and its index buffer behind and those are exactly the fields a pass that
     * binds nothing would otherwise inherit. The arena is never closed: it belongs to the thread, not
     * to the pass.
     */
    private record DrawCallBuffer(Arena arena, MemorySegment segment, Thread owner) {

        private static final ThreadLocal<ArrayDeque<DrawCallBuffer>> POOL =
                ThreadLocal.withInitial(ArrayDeque::new);

        static DrawCallBuffer acquire() {
            Thread owner = Thread.currentThread();
            ArrayDeque<DrawCallBuffer> pool = POOL.get();
            DrawCallBuffer buffer = pool.pollLast();

            if (buffer == null) {
                Arena arena = Arena.ofConfined();
                buffer = new DrawCallBuffer(arena, arena.allocate(WmNative.DRAW_CALL), owner);
            }

            // Every field of a zeroed draw call is the "nothing bound" value: slot kind
            // `DRAW_BINDING_NONE`, a null pipeline, a null index buffer, an empty vertex mask. The
            // previous pass left its own in there, and a pass that binds nothing would inherit them.
            buffer.segment.fill((byte) 0);
            return buffer;
        }

        /**
         * Hands the buffer back to this thread's pool, where the next pass picks it up.
         *
         * <p>A confined arena belongs to the thread that made it, so a pass closed somewhere else
         * leaves its buffer to the garbage collector rather than putting an arena another thread
         * cannot touch into that thread's pool. Blaze3D closes its passes on the render thread, which
         * is the thread that opened them, so this is a guard rather than a path.
         */
        void release() {
            if (owner == Thread.currentThread()) {
                POOL.get().addLast(this);
            }
        }
    }

    /** `struct DrawCall`, written in place per draw. */
    private final DrawCallBuffer drawCallBuffer;
    private final MemorySegment drawCall;

    /**
     * The bindings the pass has been given, by the name Minecraft binds them under.
     *
     * Sticky, the way OpenGL's state is: a draw reuses whatever was bound before it, and a pipeline
     * change re-emits all of it, because a slot means something different under a different plan.
     * Uniforms and texel buffers take one slot each; a combined sampler is a pair and lives in
     * {@link #boundSamplers}, because the plan splits it into a texture slot and a sampler slot.
     */
    private final Map<String, Bound> boundBindings = new HashMap<>();

    /** The combined samplers the pass has been given, by the name Minecraft binds them under. */
    private final Map<String, Sampled> boundSamplers = new HashMap<>();

    /** One remembered binding: what it is, and for a buffer, which slice of it. */
    private record Bound(int kind, MemorySegment resource, long offset, long length) {
        static Bound buffer(MemorySegment resource, long offset, long length) {
            return new Bound(WmNative.DRAW_BINDING_BUFFER, resource, offset, length);
        }

        static Bound texture(MemorySegment resource) {
            return new Bound(WmNative.DRAW_BINDING_TEXTURE, resource, 0L, 0L);
        }

        static Bound sampler(MemorySegment resource) {
            return new Bound(WmNative.DRAW_BINDING_SAMPLER, resource, 0L, 0L);
        }
    }

    /** A combined sampler, remembered as the two bindings the plan splits it into. */
    private record Sampled(Bound texture, Bound sampler) {
    }

    /** The pipeline's binding slots, or null when it could not be resolved. */
    private PlanBindings bindings;

    /** Kept for diagnostics: an `Animate ...` pass has its target dumped once it has been submitted. */
    private final String label;
    private final MemorySegment nativeColorTexture;

    /**
     * Size of the render area, i.e. of the colour target's own mip level.
     *
     * <p>`getWidth(0)` on a view is that level's width, which is also the extent wgpu gives the
     * pass, so a scissor rectangle can be clamped against it.
     */
    private final int targetWidth;
    private final int targetHeight;

    private MemorySegment activePipeline = MemorySegment.NULL;
    private int openDebugGroups;

    public WgpuRenderPass(
            WgpuDevice device,
            WgpuCommandEncoder encoder,
            @NonNull Supplier<String> label,
            @NonNull GpuTextureView colorTexture,
            OptionalInt clearColor,
            GpuTextureView depthTexture,
            OptionalDouble clearDepth) {
        this.device = device;
        this.encoder = encoder;
        this.label = label.get();
        this.nativeColorTexture = ((WgpuTextureView) colorTexture).texture().nativeTexture();
        this.targetWidth = colorTexture.getWidth(0);
        this.targetHeight = colorTexture.getHeight(0);
        this.wantsDepth = depthTexture != null;
        this.drawCallBuffer = DrawCallBuffer.acquire();
        this.drawCall = drawCallBuffer.segment();
        this.nativePass = buildDescriptor(this.label, colorTexture, clearColor, depthTexture, clearDepth);
        describeOnce(this.label, colorTexture, depthTexture);
    }

    /**
     * Says which render target each distinct pass draws into, once per pass label.
     *
     * <p>Diagnostics. "The GUI is missing" is either "the GUI went into a different texture than the
     * one that gets presented" or "something drew over it", and the target the pass names is what
     * tells those apart.
     */
    private static void describeOnce(String label, GpuTextureView color, GpuTextureView depth) {
        if (!Diagnostics.isEnabled()) {
            return;
        }
        if (!DESCRIBED.add(label + " -> " + color.getWidth(0) + "x" + color.getHeight(0))) {
            return;
        }

        dev.birb.wgpu.WgpuMcMod.LOGGER.info(
                "wgpu: pass {} draws into a {}x{} {} texture (depth: {})",
                label,
                color.getWidth(0),
                color.getHeight(0),
                ((WgpuTextureView) color).texture().getFormat(),
                depth == null ? "none" : ((WgpuTextureView) depth).texture().getFormat());
    }

    private static final java.util.Set<String> DESCRIBED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private MemorySegment buildDescriptor(
            String label,
            GpuTextureView colorTexture,
            @NonNull OptionalInt clearColor,
            GpuTextureView depthTexture,
            OptionalDouble clearDepth) {
        try (Arena arena = Arena.ofConfined()) {
            // struct BlazeAttachmentDescriptor_f32x4 { const uint8_t *texture_view; const float (*clear_value)[4]; }
            MemorySegment colorAttachment = arena.allocate(WmNative.ATTACHMENT_F32X4);
            colorAttachment.set(WmNative.PTR, WmNative.FIELD_TEXTURE_VIEW, nativeViewOf(colorTexture));

            MemorySegment clearColorPtr = MemorySegment.NULL;
            if (clearColor.isPresent()) {
                int argb = clearColor.getAsInt();
                clearColorPtr = arena.allocate(CLEAR_COLOR_LAYOUT);
                clearColorPtr.set(ValueLayout.JAVA_FLOAT, 0L, ARGB.red(argb) / 255.0f);
                clearColorPtr.set(ValueLayout.JAVA_FLOAT, 4L, ARGB.green(argb) / 255.0f);
                clearColorPtr.set(ValueLayout.JAVA_FLOAT, 8L, ARGB.blue(argb) / 255.0f);
                clearColorPtr.set(ValueLayout.JAVA_FLOAT, 12L, ARGB.alpha(argb) / 255.0f);
            }
            colorAttachment.set(WmNative.PTR, WmNative.FIELD_CLEAR_VALUE, clearColorPtr);

            // struct RawArray_BlazeAttachmentDescriptor_f32x4 { const struct *contents; uint64_t size; }
            MemorySegment attachments = arena.allocate(WmNative.RAW_ARRAY);
            WmNative.writeRawArray(attachments, 0L, colorAttachment, 1L);

            // struct BlazeAttachmentDescriptor_f64 { const uint8_t *texture_view; const double *clear_value; }
            MemorySegment depthAttachment = MemorySegment.NULL;
            if (depthTexture != null) {
                depthAttachment = arena.allocate(WmNative.ATTACHMENT_F64);
                depthAttachment.set(WmNative.PTR, WmNative.FIELD_TEXTURE_VIEW, nativeViewOf(depthTexture));

                MemorySegment clearDepthPtr = MemorySegment.NULL;
                if (clearDepth.isPresent()) {
                    clearDepthPtr = arena.allocate(ValueLayout.JAVA_DOUBLE);
                    clearDepthPtr.set(ValueLayout.JAVA_DOUBLE, 0L, clearDepth.getAsDouble());
                }
                depthAttachment.set(WmNative.PTR, WmNative.FIELD_CLEAR_VALUE, clearDepthPtr);
            }

            // struct BlazeRenderPassDescriptor { const RawArray *attachments; const BlazeAttachmentDescriptor_f64 *depth_attachment; }
            MemorySegment descriptor = arena.allocate(WmNative.RENDER_PASS_DESCRIPTOR);
            descriptor.set(WmNative.PTR, WmNative.FIELD_TEXTURE_VIEW, attachments);
            descriptor.set(WmNative.PTR, WmNative.FIELD_CLEAR_VALUE, depthAttachment);

            return (MemorySegment) invoke(
                    WmNative.createRenderPass,
                    encoder.nativeEncoder(),
                    descriptor,
                    NativeNames.utf8(label));
        }
    }

    private static @NonNull MemorySegment nativeViewOf(GpuTextureView view) {
        return ((WgpuTextureView) view).nativeView();
    }

    /** {@code invokeExact} declares a checked {@link Throwable}; a failure here is fatal. */
    private static Object invoke(MethodHandle handle, Object... args) {
        try {
            return handle.invokeWithArguments(args);
        } catch (Throwable throwable) {
            throw new IllegalStateException("wgpu native call failed", throwable);
        }
    }

    @Override
    public void pushDebugGroup(Supplier<String> label) {
        openDebugGroups++;
    }

    @Override
    public void popDebugGroup() {
        if (openDebugGroups > 0) {
            openDebugGroups--;
        }
    }

    @Override
    public void setPipeline(RenderPipeline pipeline) {
        // The variant this pass needs, which the compiled pipeline writes on demand the first time:
        // a pass with a depth attachment and one without cannot share a `wgpu::RenderPipeline`.
        WgpuCompiledRenderPipeline compiled =
                WgpuCompiledRenderPipeline.of(device, pipeline, device.defaultShaderSource(), wantsDepth);
        this.activePipeline = compiled.forDepth(wantsDepth);

        // The slots of the new plan, and everything bound so far re-emitted into them: the same
        // binding name sits in a different slot under a different pipeline, so what was written for
        // the previous one is meaningless here.
        PlanBindings slots = compiled.slotsForJava();
        if (slots == null) {
            throw new IllegalStateException("wgpu: " + pipeline.getLocation()
                    + " has more bindings than the draw call can carry; see the log");
        }
        this.bindings = slots;
        clearBindings();
        for (Map.Entry<String, Bound> entry : boundBindings.entrySet()) {
            writeBinding(entry.getKey(), entry.getValue());
        }
        for (Map.Entry<String, Sampled> entry : boundSamplers.entrySet()) {
            writeSampled(entry.getKey(), entry.getValue());
        }
    }

    @Override
    public void bindTexture(String name, GpuTextureView textureView, GpuSampler sampler) {
        if (textureView == null || sampler == null) {
            return;
        }

        if (Diagnostics.isEnabled() && BOUND.add(label + " " + name)) {
            // Diagnostics: which texture a sampler actually got. A surface that is drawn in one
            // flat colour is either "the shader sampled nothing" or "the shader sampled something
            // else", and the label is what tells those apart - the block atlas and the lightmap are
            // both bound to a terrain pass, and swapping them would look exactly like this.
            WgpuTexture texture = ((WgpuTextureView) textureView).texture();
            dev.birb.wgpu.WgpuMcMod.LOGGER.info(
                    "wgpu: pass {} binds {} to {} ({}x{}, {})",
                    label,
                    name,
                    texture.getLabel(),
                    texture.getWidth(0),
                    texture.getHeight(0),
                    texture.getFormat());
        }

        // One name, two slots: the plan declares a combined sampler as a texture and a sampler, and
        // this is the pair under the name Minecraft binds it with. Remembered as a pair so a pipeline
        // change can re-emit both halves - the plan's slots are per pipeline, but the pair is not.
        Sampled pair = new Sampled(
                Bound.texture(nativeViewOf(textureView)),
                Bound.sampler(((WgpuSampler) sampler).nativeSampler()));
        boundSamplers.put(name, pair);
        writeSampled(name, pair);
    }

    /** Writes a combined sampler into the two slots its name has in the current plan. */
    private void writeSampled(String name, Sampled pair) {
        int[] slots = bindings == null ? null : bindings.of(name);
        if (slots == null || slots.length < 2) {
            return;
        }

        writeSlot(slots[0], pair.texture());
        writeSlot(slots[1], pair.sampler());
    }

    /** Diagnostics: each `pass + sampler` pair is reported once. */
    private static final java.util.Set<String> BOUND = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @Override
    public void setUniform(String name, @NonNull GpuBuffer value) {
        setUniform(name, value.slice());
    }

    @Override
    public void setUniform(String name, GpuBufferSlice value) {
        // Diagnostics: the offset Minecraft is binding this uniform at, which is the number the
        // dynamic-offset path has to carry through to `set_bind_group` unchanged.
        if (Diagnostics.isEnabled()) {
            reportUniformOffset(name, value.offset());
        }

        if (Diagnostics.isEnabled() && shouldDumpUniform(name)) {
            dumpUniform(name, value);
        }

        // wgpu wants uniform ranges to be a multiple of 16 bytes, but rounding *up* can run past
        // the end of the buffer - a slice whose last byte is the buffer's last byte became a
        // binding four bytes too large, which wgpu refuses. The rounding stops at the buffer.
        long available = ((WgpuBuffer) value.buffer()).size() - value.offset();
        long rounded = Mth.roundToward((int) Math.min(value.length(), available), 16);

        Bound binding = Bound.buffer(
                ((WgpuBuffer) value.buffer()).nativeBuffer(),
                value.offset(),
                Math.min(rounded, Math.max(available, 1)));

        boundBindings.put(name, binding);
        writeBinding(name, binding);
    }

    /** Writes a remembered binding into the slot its name has in the current pipeline's plan. */
    private void writeBinding(String name, Bound binding) {
        if (binding == null) {
            return;
        }

        int[] slots = bindings == null ? null : bindings.of(name);
        if (slots == null || slots.length == 0) {
            return;
        }

        writeSlot(slots[0], binding);
    }

    /** Writes one binding into one slot of the draw call. */
    private void writeSlot(int slot, Bound binding) {
        if (binding == null || bindings == null || slot < 0 || slot >= bindings.getCount()) {
            return;
        }

        long base = WmNative.DRAW_CALL_BINDINGS + WmNative.elementOffset(WmNative.DRAW_BINDING, slot);
        drawCall.set(WmNative.INT, base + WmNative.DRAW_BINDING_KIND, binding.kind());
        drawCall.set(WmNative.ADDRESS, base + WmNative.DRAW_BINDING_RESOURCE, binding.resource());
        drawCall.set(WmNative.LONG, base + WmNative.DRAW_BINDING_OFFSET, binding.offset());
        drawCall.set(WmNative.LONG, base + WmNative.DRAW_BINDING_LENGTH, binding.length());
    }

    /** Marks every binding slot empty, which a pipeline change has to do before re-emitting. */
    private void clearBindings() {
        if (bindings == null) {
            return;
        }

        for (int slot = 0; slot < bindings.getCount(); slot++) {
            long base = WmNative.DRAW_CALL_BINDINGS + WmNative.elementOffset(WmNative.DRAW_BINDING, slot);
            drawCall.set(WmNative.INT, base + WmNative.DRAW_BINDING_KIND, WmNative.DRAW_BINDING_NONE);
            drawCall.set(WmNative.ADDRESS, base + WmNative.DRAW_BINDING_RESOURCE, MemorySegment.NULL);
            drawCall.set(WmNative.LONG, base + WmNative.DRAW_BINDING_OFFSET, 0L);
            drawCall.set(WmNative.LONG, base + WmNative.DRAW_BINDING_LENGTH, 0L);
        }
    }

    /**
     * Diagnostics: reads a uniform's data back and logs it as floats.
     *
     * <p>A world that is drawn in one flat colour is a shader reading a uniform that never arrived,
     * and the values are the only thing that says which one: the terrain shader's fog distances and
     * colour decide whether anything in front of the camera survives to be drawn, and its section
     * matrix decides where it lands. The readback goes through {@code read_buffer}, which allocates
     * a staging buffer, submits a command buffer and waits for the GPU - so it is rationed twice
     * over, once by name and once by a readbacks-per-second budget.
     */
    private void dumpUniform(String name, @NonNull GpuBufferSlice value) {
        int length = (int) Math.min(value.length(), 256);

        // Closed rather than left to the GC: one of these was leaking a confined arena per uniform
        // bind, and a bind can happen thousands of times per resource reload.
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment staging = arena.allocate(length);

            Boolean filled = (Boolean) invoke(
                    WmNative.readBuffer,
                    device.renderer(),
                    ((WgpuBuffer) value.buffer()).nativeBuffer(),
                    value.offset(),
                    (long) length,
                    staging);

            if (filled == null || !filled) {
                if (UNIFORM_READBACK_FAILED.add(name)) {
                    dev.birb.wgpu.WgpuMcMod.LOGGER.warn(
                            "wgpu: could not read uniform {} back; it is one Rust will not map",
                            name);
                }
                return;
            }

            // Both spellings, because a std140 block mixes them: `Globals.CameraBlockPos` and
            // `ChunkSection.TextureSize` are integers, and reading them as floats prints zero.
            StringBuilder floats = new StringBuilder();
            StringBuilder ints = new StringBuilder();
            for (int i = 0; i + 4 <= length; i += 4) {
                floats.append(String.format(java.util.Locale.ROOT, "%.4g ", staging.get(ValueLayout.JAVA_FLOAT, i)));
                ints.append(staging.get(ValueLayout.JAVA_INT, i)).append(' ');
            }

            dev.birb.wgpu.WgpuMcMod.LOGGER.info(
                    "wgpu: uniform {} at {} ({} bytes) in pass '{}'\n  floats: {}\n  ints:   {}",
                    name, value.offset(), length, label, floats.toString().trim(), ints.toString().trim());
        }
    }

    /** Diagnostics: each uniform name is reported once, and these again whenever they change. */
    private static final java.util.Set<String> UNIFORMS_REPORTED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Diagnostics: the last offset each uniform was bound at, so a move can be reported. */
    private static final java.util.Map<String, Long> UNIFORM_OFFSETS =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static volatile long OFFSETS_REPORTED_AT = 0L;

    /** Diagnostics: reports a uniform whose offset has moved, at most once a second overall. */
    private static void reportUniformOffset(String name, long offset) {
        Long previous = UNIFORM_OFFSETS.put(name, offset);
        if (previous != null && previous == offset) {
            return;
        }

        long now = System.nanoTime();
        if (now - OFFSETS_REPORTED_AT < 1_000_000_000L) {
            return;
        }
        OFFSETS_REPORTED_AT = now;

        dev.birb.wgpu.WgpuMcMod.LOGGER.info(
                "wgpu: uniform {} is bound at offset {}", name, offset);
    }

    /**
     * Whether to report a uniform now.
     *
     * <p>Most uniforms are only interesting the first time they are bound. The ones that decide
     * what a world looks like are not: the fog distances are meaningless on the title screen (all
     * of them are {@code FLT_MAX}, i.e. fog off) and only say anything once a level is loaded, and
     * the cloud block is rebuilt every frame. Those are reported again, at most once a second, and
     * the rest stay once per name.
     *
     * <p>Keyed by name and <em>not</em> by offset, which is what an earlier version did: Minecraft
     * binds one uniform per sprite while it animates an atlas, each at its own offset, so an offset
     * in the key turned every sprite's bind into a fresh readback - 4722 of them for the blocks
     * atlas alone, each allocating, submitting and waiting. That is what took the game's memory
     * into the tens of gigabytes during a resource reload.
     */
    private static boolean shouldDumpUniform(String name) {
        if (!UNIFORMS_REPORTED.add(name)) {
            if (!name.startsWith("Fog") && !name.startsWith("Cloud")) {
                return false;
            }

            long now = System.nanoTime();
            Long last = UNIFORM_REPORTED_AT.get(name);
            if (last != null && now - last < 1_000_000_000L) {
                return false;
            }
            UNIFORM_REPORTED_AT.put(name, now);
        }

        // The budget is the backstop: a name nobody expected to be bound in a loop cannot get past
        // it, whatever the key is.
        return takeReadbackBudget();
    }

    /** How many readbacks a second the diagnostics may cost, whatever asks for them. */
    private static final int READBACKS_PER_SECOND = 4;

    private static boolean takeReadbackBudget() {
        long second = System.nanoTime() / 1_000_000_000L;

        synchronized (READBACK_BUDGET) {
            if (READBACK_BUDGET[0] != second) {
                READBACK_BUDGET[0] = second;
                READBACK_BUDGET[1] = 0;
            }

            if (READBACK_BUDGET[1] >= READBACKS_PER_SECOND) {
                if (READBACK_EXHAUSTED != second) {
                    READBACK_EXHAUSTED = second;
                    dev.birb.wgpu.WgpuMcMod.LOGGER.warn(
                            "wgpu: the uniform readbacks hit their budget of {} a second; the rest of this second's uniforms go unreported",
                            READBACKS_PER_SECOND);
                }
                return false;
            }

            READBACK_BUDGET[1]++;
            return true;
        }
    }

    private static final long[] READBACK_BUDGET = {0L, 0L};

    /**
     * The last second the budget was reported for.
     *
     * A field rather than a set of seconds: the set grew by one entry per second for as long as the
     * game ran, which is a leak whose whole purpose was to log a warning.
     */
    private static volatile long READBACK_EXHAUSTED = -1L;

    private static final java.util.Map<String, Long> UNIFORM_REPORTED_AT =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Uniforms whose readback was refused, so the warning is logged once each. */
    private static final java.util.Set<String> UNIFORM_READBACK_FAILED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    @Override
    public void setViewport(int x, int y, int width, int height) {
        if (Diagnostics.isEnabled() && VIEWPORTS.add(x + "," + y + "," + width + "," + height)) {
            dev.birb.wgpu.WgpuMcMod.LOGGER.info("wgpu: viewport {} {} {} {}", x, y, width, height);
        }
    }

    private static final java.util.Set<String> VIEWPORTS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Restricts drawing to [x, y, width, height] of the colour target.
     *
     * <p>The rectangle arrives in OpenGL's window coordinates, measured from the target's origin
     * row. That is the row this backend renders into - the clip-space y negation in
     * {@code preprocessing::EmulateGlClipSpace} is what makes it so - and the rectangle is
     * therefore forwarded as it is. The two callers agree on the convention: {@code GuiRenderer}
     * builds its rectangle as {@code windowHeight - bottom * guiScale}, and {@code GuiItemAtlas}
     * renders items with {@code textureSize - bottom}.
     *
     * <p>What is <em>not</em> forwarded as it is, is the rectangle itself: OpenGL clamps a scissor
     * box that reaches outside the framebuffer, wgpu rejects it, and Minecraft's caller does not
     * clamp (`GuiGraphicsExtractor` hands over whole rectangles from screen space, which a scaled
     * GUI can push past the edge). So the intersection with the target is computed here.
     */
    @Override
    public void enableScissor(int x, int y, int width, int height) {
        // OpenGL's scissor box is [x, x + width) x [y, y + height), and an empty box draws nothing;
        // wgpu accepts a zero-sized one, so an empty intersection needs no special case.
        int left = Math.max(0, x);
        int top = Math.max(0, y);
        int right = Math.min(targetWidth, x + Math.max(0, width));
        int bottom = Math.min(targetHeight, y + Math.max(0, height));

        invoke(
                WmNative.setScissorRect,
                nativePass,
                left,
                top,
                Math.max(0, right - left),
                Math.max(0, bottom - top));

        if (Diagnostics.isEnabled() && SCISSORS.add(x + "," + y + "," + width + "," + height)) {
            dev.birb.wgpu.WgpuMcMod.LOGGER.info(
                    "wgpu: scissor {} {} {} {} -> {} {} {} {} of {}x{}",
                    x, y, width, height, left, top, Math.max(0, right - left), Math.max(0, bottom - top), targetWidth, targetHeight);
        }
    }

    private static final java.util.Set<String> SCISSORS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @Override
    public void disableScissor() {
        // wgpu's scissor is per draw call and persists inside a pass, exactly like OpenGL's, so
        // turning it off has to put the whole target back rather than leave the last box in place.
        invoke(WmNative.setScissorRect, nativePass, 0, 0, targetWidth, targetHeight);
    }

    @Override
    public void setVertexBuffer(int slot, GpuBuffer vertexBuffer) {
        WgpuBuffer buffer = (WgpuBuffer) vertexBuffer;
        long base = WmNative.DRAW_CALL_VERTEX_BUFFERS + WmNative.elementOffset(WmNative.DRAW_VERTEX_BUFFER, slot);

        drawCall.set(WmNative.ADDRESS, base, buffer.nativeBuffer());
        drawCall.set(WmNative.LONG, base + 8L, 0L);
        drawCall.set(WmNative.LONG, base + 16L, buffer.size());

        int mask = drawCall.get(WmNative.INT, WmNative.DRAW_CALL_VERTEX_BUFFER_MASK);
        drawCall.set(WmNative.INT, WmNative.DRAW_CALL_VERTEX_BUFFER_MASK, mask | (1 << slot));
    }

    @Override
    public void setIndexBuffer(GpuBuffer indexBuffer, VertexFormat.IndexType indexType) {
        drawCall.set(WmNative.ADDRESS, WmNative.DRAW_CALL_INDEX_BUFFER, ((WgpuBuffer) indexBuffer).nativeBuffer());
        drawCall.set(WmNative.INT, WmNative.DRAW_CALL_INDEX_FORMAT,
                indexType == VertexFormat.IndexType.INT
                        ? WmNative.INDEX_FORMAT_UINT32
                        : WmNative.INDEX_FORMAT_UINT16);
    }

    @Override
    public void drawIndexed(int baseVertex, int firstIndex, int indexCount, int instanceCount) {
        record(firstIndex, indexCount, baseVertex, instanceCount, true);
    }

    @Override
    public void draw(int firstVertex, int vertexCount) {
        record(firstVertex, vertexCount, 0, 1, false);
    }

    /**
     * Fills the draw call's parameters and records it, which is the whole per-draw ABI.
     *
     * The bindings are already in the buffer - they were written as they were bound - so a draw adds
     * the parameters and the count, and `draw_call` does the rest: the pipeline if it changed, the
     * vertex and index buffers, the bind groups (looked up or built once and then reused by the
     * pass) and finally the draw itself.
     */
    private void record(int first, int count, int baseVertex, int instanceCount, boolean indexed) {
        if (activePipeline.equals(MemorySegment.NULL) || bindings == null) {
            // Blaze3D never draws without a pipeline; if it ever does, the call would dereference a
            // null pipeline inside wgpu.
            dev.birb.wgpu.WgpuMcMod.LOGGER.error("wgpu: a draw arrived with no pipeline bound");
            return;
        }

        drawCall.set(WmNative.ADDRESS, WmNative.DRAW_CALL_PIPELINE, activePipeline);
        drawCall.set(WmNative.INT, WmNative.DRAW_CALL_FIRST, first);
        drawCall.set(WmNative.INT, WmNative.DRAW_CALL_COUNT, count);
        drawCall.set(WmNative.INT, WmNative.DRAW_CALL_BASE_VERTEX, baseVertex);
        drawCall.set(WmNative.INT, WmNative.DRAW_CALL_INSTANCE_COUNT, instanceCount);
        drawCall.set(WmNative.INT, WmNative.DRAW_CALL_INDEXED, indexed ? 1 : 0);
        drawCall.set(WmNative.INT, WmNative.DRAW_CALL_BINDINGS_LEN, bindings.getCount());

        invoke(WmNative.drawCall, device.renderer(), nativePass, drawCall);
    }

    /**
     * Replays a multi-draw one draw at a time.
     *
     * <p>Batching would need the indirect/instanced path, which the Rust ABI does not expose yet;
     * replaying keeps rendering correct, only less batched. Per-draw uniform uploads are honoured
     * so annotated draws still see their own uniforms.
     */
    @Override
    public <T> void drawMultipleIndexed(
            @NonNull Collection<RenderPass.Draw<T>> draws,
            GpuBuffer defaultIndexBuffer,
            VertexFormat.IndexType defaultIndexType,
            Collection<String> dynamicUniforms,
            T uniformArgument) {
        for (RenderPass.Draw<T> draw : draws) {
            GpuBuffer indexBuffer = draw.indexBuffer() != null ? draw.indexBuffer() : defaultIndexBuffer;
            if (indexBuffer == null) {
                continue;
            }
            VertexFormat.IndexType indexType =
                    draw.indexType() != null ? draw.indexType() : defaultIndexType;
            if (indexType == null) {
                indexType = VertexFormat.IndexType.SHORT;
            }

            if (draw.uniformUploaderConsumer() != null) {
                draw.uniformUploaderConsumer()
                        .accept(uniformArgument, (name, buffer) -> setUniform(name, buffer));
            }

            setVertexBuffer(draw.slot(), draw.vertexBuffer());
            setIndexBuffer(indexBuffer, indexType);
            drawIndexed(draw.baseVertex(), draw.firstIndex(), draw.indexCount(), 1);
        }
    }

    @Override
    public boolean isClosed() {
        return closed.get();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            invoke(WmNative.dropRenderPass, nativePass);
            // The bind groups went with the pass itself and the draw call buffer goes back to the
            // pool for the next pass on this thread: nothing here frees anything per draw.
            drawCallBuffer.release();
            encoder.onRenderPassClosed();
            // After the pass is submitted, not before: the atlas this dumps is a render result.
            if (Diagnostics.isEnabled()) {
                Diagnostics.dumpPassTarget(device.renderer(), label, nativeColorTexture);
            }
        }
    }
}



