package dev.birb.wgpu.gui

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import dev.birb.wgpu.gui.options.BoolOption
import dev.birb.wgpu.gui.options.EnumOption
import dev.birb.wgpu.gui.options.FloatOption
import dev.birb.wgpu.gui.options.IntOption
import dev.birb.wgpu.gui.options.Option
import dev.birb.wgpu.gui.options.RustOptionInfo
import dev.birb.wgpu.rust.WgpuNative
import net.minecraft.client.AttackIndicatorStatus
import net.minecraft.client.CloudStatus
import net.minecraft.client.GraphicsStatus
import net.minecraft.client.Minecraft
import net.minecraft.client.Options
import net.minecraft.client.ParticleStatus
import net.minecraft.network.chat.Component

class OptionPages : Iterable<OptionPages.Page> {
    private val pages: MutableList<Page> = ArrayList()

    init {
        pages.add(createGeneral())
        pages.add(createElectrum())
        pages.add(createQuality())
    }

    fun getDefault(): Page = pages[0]

    fun isChanged(): Boolean = pages.any { it.isChanged() }

    fun apply() = pages.forEach { it.apply() }

    fun undo() = pages.forEach { it.undo() }

    override fun iterator(): Iterator<Page> = pages.iterator()

    private fun createGeneral(): Page {
        val page = Page(Component.literal("General"))
        val mc = Minecraft.getInstance()
        val options = mc.options

        page.add(IntOption.Builder()
            .setName(Component.translatable("options.renderDistance"))
            .setOption(options.renderDistance())
            .setFormatter { integer -> Component.translatable("options.chunks", integer) }
            .setRange(2, 32)
            .build())

        page.add(IntOption.Builder()
            .setName(Component.translatable("options.simulationDistance"))
            .setOption(options.simulationDistance())
            .setFormatter { integer -> Component.translatable("options.chunks", integer) }
            .setRange(5, 16)
            .build())

        page.add(IntOption.Builder()
            .setName(Component.translatable("options.gamma"))
            .setAccessors(
                { (options.gamma().get() * 100).toInt() },
                { integer -> options.gamma().set(integer / 100.0) }
            )
            .setFormatter { integer ->
                when (integer) {
                    0 -> Component.translatable("options.gamma.min")
                    50 -> Component.translatable("options.gamma.default")
                    100 -> Component.translatable("options.gamma.max")
                    else -> Component.literal("$integer%")
                }
            }
            .setRange(0, 100)
            .build())

        page.space()
        page.add(IntOption.Builder()
            .setName(Component.translatable("options.guiScale"))
            .setOption(options.guiScale()) { mc.resizeDisplay() }
            .setFormatter { integer -> Component.literal(if (integer == 0) "Auto" else "${integer}x") }
            .setRange(0, 4)
            .build())

        page.add(BoolOption.Builder()
            .setName(Component.translatable("options.fullscreen"))
            .setOption(options.fullscreen())
            .build())

        page.add(BoolOption.Builder()
            .setName(Component.translatable("options.vsync"))
            .setOption(options.enableVsync())
            .build())

        page.add(IntOption.Builder()
            .setName(Component.translatable("options.framerateLimit"))
            .setOption(options.framerateLimit())
            .setFormatter { integer ->
                if (integer == 260) Component.translatable("options.framerateLimit.max")
                else Component.literal(integer.toString())
            }
            .setRange(5, 260)
            .setStep(5)
            .build())

        page.space()
        page.add(BoolOption.Builder()
            .setName(Component.translatable("options.viewBobbing"))
            .setOption(options.bobView())
            .build())

        page.add(EnumOption.Builder(AttackIndicatorStatus::class.java)
            .setName(Component.translatable("options.attackIndicator"))
            .setOption(options.attackIndicator())
            .setFormatter { status -> Component.translatable(status.key) }
            .build())

        page.add(BoolOption.Builder()
            .setName(Component.translatable("options.autosaveIndicator"))
            .setOption(options.showAutosaveIndicator())
            .build())

        return page
    }

    private fun createElectrum(): Page {
        val page = Page(Component.literal("Electrum"))
        val rustSettings = WgpuNative.getSettings()
        val options: List<Option<*>> = GSON.fromJson(rustSettings, SETTINGS_TYPE_TOKEN.type)
        for (option in options) {
            page.add(option)
        }
        return page
    }

    private fun createQuality(): Page {
        val page = Page(Component.literal("Quality"))
        val options = Minecraft.getInstance().options

        page.add(EnumOption.Builder(GraphicsStatus::class.java)
            .setName(Component.translatable("options.graphics"))
            .setOption(options.graphicsMode())
            .setFormatter { graphicsStatus -> Component.translatable(graphicsStatus.key) }
            .build())

        page.space()
        page.add(EnumOption.Builder(CloudStatus::class.java)
            .setName(Component.translatable("options.renderClouds"))
            .setOption(options.cloudStatus())
            .setFormatter { cloudStatus -> Component.translatable(cloudStatus.key) }
            .build())

        page.add(EnumOption.Builder(ParticleStatus::class.java)
            .setName(Component.translatable("options.particles"))
            .setOption(options.particles())
            .setFormatter { particleStatus -> Component.translatable(particleStatus.key) }
            .build())

        page.add(BoolOption.Builder()
            .setName(Component.translatable("options.ao"))
            .setOption(options.ambientOcclusion())
            .build())

        page.add(IntOption.Builder()
            .setName(Component.translatable("options.biomeBlendRadius"))
            .setOption(options.biomeBlendRadius())
            .setFormatter { integer -> Component.translatable("options.biomeBlendRadius.${integer * 2 + 1}") }
            .setRange(0, 7)
            .build())

        page.space()
        page.add(IntOption.Builder()
            .setName(Component.translatable("options.entityDistanceScaling"))
            .setAccessors(
                { (options.entityDistanceScaling().get() * 100).toInt() },
                { integer -> options.entityDistanceScaling().set(integer / 100.0) }
            )
            .setFormatter { integer -> Component.literal("$integer%") }
            .setRange(50, 500)
            .setStep(25)
            .build())

        page.add(BoolOption.Builder()
            .setName(Component.translatable("options.entityShadows"))
            .setOption(options.entityShadows())
            .build())

        page.space()
        page.add(IntOption.Builder()
            .setName(Component.translatable("options.mipmapLevels"))
            .setOption(options.mipmapLevels())
            .setFormatter { integer -> Component.literal("${integer}x") }
            .setRange(0, 4)
            .build())

        return page
    }

    class Page(val name: Component) : Iterable<List<Option<*>>> {
        private val groups: MutableList<MutableList<Option<*>>> = ArrayList()

        init {
            space()
        }

        fun add(option: Option<*>) {
            groups[groups.size - 1].add(option)
        }

        fun space() {
            groups.add(ArrayList())
        }

        fun isChanged(): Boolean {
            for (group in groups) {
                for (option in group) {
                    if (option.isChanged()) return true
                }
            }
            return false
        }

        fun apply() {
            if (name.string == "Electrum") {
                val options = groups.flatten()
                val json = GSON.toJson(options, SETTINGS_TYPE_TOKEN.type)
                WgpuNative.sendSettings(json)
                return
            }

            for (group in groups) {
                for (option in group) {
                    option.apply()
                }
            }
        }

        fun undo() {
            for (group in groups) {
                for (option in group) {
                    option.undo()
                }
            }
        }

        override fun iterator(): Iterator<List<Option<*>>> = groups.iterator()
    }

    companion object {
        private val SETTINGS_STRUCTURE_TYPE_TOKEN = object : TypeToken<Map<String, RustOptionInfo>>() {}
        private val SETTINGS_TYPE_TOKEN = object : TypeToken<List<Option<*>>>() {}

        private val GSON = GsonBuilder()
            .registerTypeAdapter(SETTINGS_TYPE_TOKEN.type, Option.OptionSerializerDeserializer())
            .create()

        val SETTINGS_STRUCTURE: Map<String, RustOptionInfo> = GSON.fromJson(
            WgpuNative.getSettingsStructure(),
            SETTINGS_STRUCTURE_TYPE_TOKEN.type
        )
    }
}
