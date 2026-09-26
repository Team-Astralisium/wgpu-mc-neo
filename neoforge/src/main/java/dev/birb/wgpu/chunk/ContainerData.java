package dev.birb.wgpu.chunk;

import dev.birb.wgpu.WgpuMcMod;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.util.BitStorage;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;

/**
 * A section container's palette and storage, as the game holds them.
 *
 * <p>Both live on {@code PalettedContainer$Data}, which 26.1 declares as a <em>private</em> nested
 * class. That rules out the two usual routes at once: a mixin accessor has to name the field's exact
 * type (Mixin looks the field up by descriptor, so a wider return type finds nothing), and no class
 * outside {@code PalettedContainer} - not even one in the same package - can name a private nested
 * type at all. What is left is to look the members up once, reflectively, and keep handles to them:
 * the field read and the two record accessors run at method-handle speed, which is a handful of
 * nanoseconds against the 4096 palette lookups and bit-packing this replaced.
 *
 * <p>{@code pack()} and {@code getAll()} are the public alternatives and both are the wrong shape:
 * {@code pack} re-encodes the whole section (a fresh {@code SimpleBitStorage} per call) and
 * {@code getAll} is a lambda per position.
 */
public final class ContainerData {

    private static final VarHandle DATA;
    private static final MethodHandle PALETTE;
    private static final MethodHandle STORAGE;
    private static final Field SECTION_COPIES;

    static {
        VarHandle data = null;
        MethodHandle palette = null;
        MethodHandle storage = null;

        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(PalettedContainer.class, MethodHandles.lookup());
            Class<?> dataClass = PalettedContainer.class.getDeclaredField("data").getType();

            data = lookup.findVarHandle(PalettedContainer.class, "data", dataClass);
            palette = lookup.findVirtual(dataClass, "palette", MethodType.methodType(Palette.class));
            storage = lookup.findVirtual(dataClass, "storage", MethodType.methodType(BitStorage.class));
        } catch (ReflectiveOperationException | RuntimeException error) {
            // A version where these moved is a version where the section feed cannot describe a
            // section at all. Saying so once and answering null leaves the Rust terrain path off
            // instead of taking the game down; the caller logs the section it had to skip.
            WgpuMcMod.LOGGER.error("wgpu: cannot reach the palette and storage behind a section container", error);
        }

        DATA = data;
        PALETTE = palette;
        STORAGE = storage;
        SECTION_COPIES = findSectionCopies();
    }

    /**
     * The 3x3x3 section copies a rebuild snapshot holds.
     *
     * <p>Reflection for the same reason as the container's data: {@code RenderSectionRegion#sections}
     * is a private field whose element type is package-private, so no accessor outside Minecraft''s own
     * package can name it - and a class *inside* that package is rejected by the module layer, because
     * a mod exporting a package the game already has is a split package.
     */
    private static Field findSectionCopies() {
        try {
            Field field = RenderSectionRegion.class.getDeclaredField("sections");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException error) {
            WgpuMcMod.LOGGER.error("wgpu: cannot reach the section copies of a rebuild snapshot", error);
            return null;
        }
    }

    /** The snapshot''s copies, or null when they cannot be reached. */
    public static Object[] sectionCopies(RenderSectionRegion region) {
        if (SECTION_COPIES == null) {
            return null;
        }

        try {
            return (Object[]) SECTION_COPIES.get(region);
        } catch (IllegalAccessException error) {
            throw new IllegalStateException("wgpu: could not read a rebuild snapshot''s sections", error);
        }
    }

    /** Whether the snapshot''s copies can be reached; false means the live chunk has to be read. */
    public static boolean sectionsAvailable() {
        return SECTION_COPIES != null;
    }

    private ContainerData() {
    }

    /** Whether the members were found; false means every call below answers null. */
    public static boolean available() {
        return DATA != null;
    }

    /** `valueFor(index)` on this is the translation table the Rust side is handed. */
    @SuppressWarnings("unchecked")
    public static Palette<BlockState> palette(PalettedContainer<BlockState> states) {
        if (DATA == null) {
            return null;
        }

        try {
            return (Palette<BlockState>) PALETTE.invoke(DATA.get(states));
        } catch (Throwable error) {
            throw new IllegalStateException("wgpu: could not read a section's palette", error);
        }
    }

    /** The storage itself: the raw longs and the numbers that find a value in them. */
    public static BitStorage storage(PalettedContainer<BlockState> states) {
        if (DATA == null) {
            return null;
        }

        try {
            return (BitStorage) STORAGE.invoke(DATA.get(states));
        } catch (Throwable error) {
            throw new IllegalStateException("wgpu: could not read a section's storage", error);
        }
    }
}