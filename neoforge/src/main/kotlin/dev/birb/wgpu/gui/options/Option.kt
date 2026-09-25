package dev.birb.wgpu.gui.options

import com.google.gson.*
import dev.birb.wgpu.gui.OptionPages
import dev.birb.wgpu.gui.widgets.Widget
import net.minecraft.client.OptionInstance
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import java.lang.reflect.Type
import java.util.function.Consumer
import java.util.function.Supplier

abstract class Option<T>(
    val name: Component,
    val tooltip: Component,
    val requiresRestart: Boolean,
    private val getter: Supplier<T>,
    private val setter: Consumer<T>
) {
    /**
     * The renderer's own name for this setting, which is also its key in the config file.
     *
     * Kept apart from [name], which is what the player reads. The two were the same string until
     * the names became translatable, and the three places that need the renderer's spelling - the
     * JSON sent back to it, the schema lookup, and the check that keeps vanilla's vsync option in
     * step - still need it. Null for an option the renderer knows nothing about, which is every
     * option on the General and Quality pages.
     */
    var setting: String? = null
        internal set

    private var value: T = getter.get()

    /**
     * Whether the player edited this row, as opposed to its value merely differing from the setting
     * behind it.
     *
     * The two are not the same thing and treating them as one is how a change could be silently
     * undone by the screen itself: editing one row is what makes the *graphics preset* row differ
     * from its setting (every individual option calls `setGraphicsPresetToCustom`), so applying the
     * page re-applied the preset as well - and the preset sets a dozen options, the one that had
     * just been edited among them. Only rows the player touched are applied now.
     */
    private var edited = false

    fun get(): T = value

    fun set(value: T) {
        this.value = value
        this.edited = true
    }

    fun isChanged(): Boolean = edited && value != getter.get()

    fun apply() {
        if (!isChanged()) return

        setter.accept(value)

        // What was just applied is read back, so that the row shows what the setting *is* rather
        // than what it was asked to be. They differ when the source refuses the value: a vanilla
        // option logs the rejection and falls back to its initial value, which used to leave the row
        // showing the refused value with the Apply button still lit, as if the change had worked.
        value = getter.get()
        edited = false
    }

    fun undo() {
        value = getter.get()
        edited = false
    }

    /**
     * Reads the setting back into the row, for a row that was not applied.
     *
     * An apply can change rows the player never touched - the graphics preset sets a dozen of them -
     * and leaving them on their old values would make the screen disagree with the game until it was
     * reopened. A row the player *is* editing keeps its pending value: that edit has not been
     * applied yet, and losing it here is what the Undo button is for.
     */
    fun resync() {
        if (!edited) value = getter.get()
    }

    abstract fun createWidget(x: Int, y: Int, width: Int): Widget

    fun displayName(): Component {
        return if (isChanged()) {
            name.copy().append(" *").withStyle(ChatFormatting.ITALIC)
        } else {
            name
        }
    }

    @Suppress("UNCHECKED_CAST")
    abstract class Builder<B : Builder<B, T>, T : Any> {
        protected var name: Component? = null
        protected var tooltip: Component? = null
        protected var requiresRestart: Boolean = false
        protected var getter: Supplier<T>? = null
        protected var setter: Consumer<T>? = null

        /**
         * How the setting answers a slider, when the side that owns it says - see [IntSlider].
         *
         * Taken from the option itself, because only it knows: the ranges this screen used to write
         * down by hand are where a click could ask for a value the option refuses.
         */
        protected var slider: IntSlider? = null

        protected fun requireName(): Component {
            return requireNotNull(name) { "Option name must be set before build()" }
        }

        protected fun resolveTooltip(): Component {
            return tooltip ?: Component.empty()
        }

        protected fun requireGetter(): Supplier<T> {
            return requireNotNull(getter) { "Option getter must be set before build()" }
        }

        protected fun requireSetter(): Consumer<T> {
            return requireNotNull(setter) { "Option setter must be set before build()" }
        }

        fun setName(name: Component): B {
            this.name = name
            return this as B
        }

        fun setTooltip(tooltip: Component, requiresRestart: Boolean = false): B {
            this.tooltip = tooltip
            this.requiresRestart = requiresRestart
            return this as B
        }

        fun setAccessors(getter: Supplier<T>, setter: Consumer<T>): B {
            this.getter = getter
            this.setter = setter
            return this as B
        }

        fun setOption(option: OptionInstance<T>, callback: Consumer<T>? = null): B {
            this.getter = Supplier { option.get() }
            this.setter = Consumer { v ->
                option.set(v)
                callback?.accept(v)
            }

            // A vanilla option is the one case where this side does not get to decide what a slider
            // means; see [IntSlider] for what went wrong when it tried.
            this.slider = if (option.get() is Int) {
                @Suppress("UNCHECKED_CAST")
                IntSlider.of(option.values() as OptionInstance.ValueSet<Int>)
            } else {
                null
            }

            return this as B
        }

        abstract fun build(): Option<T>
    }

    @Suppress("UNCHECKED_CAST")
    class OptionSerializerDeserializer : JsonDeserializer<List<Option<*>>>, JsonSerializer<List<Option<*>>> {

        private fun deserializeOption(jsonObject: JsonObject, name: String): Option<*> {
            val structure = OptionPages.SETTINGS_STRUCTURE[name]
                ?: throw JsonParseException("Unknown option: $name")

            val type = jsonObject.getAsJsonPrimitive("type").asString

            // The wording is the language file's, and the renderer's own English is the fallback -
            // see `OptionText`, which is also what turns a setting's name into its key.
            val displayName = OptionText.name(name)
            val tooltip = OptionText.tooltip(name, structure.desc)

            val option: Option<*> = when (type) {
                "bool" -> {
                    var value = jsonObject.getAsJsonPrimitive("value").asBoolean
                    BoolOption(
                        displayName,
                        tooltip,
                        structure.needsRestart,
                        { value },
                        { newValue -> value = newValue }
                    )
                }
                "float" -> {
                    var value = jsonObject.getAsJsonPrimitive("value").asDouble
                    val min = jsonObject.getAsJsonPrimitive("min").asDouble
                    val max = jsonObject.getAsJsonPrimitive("max").asDouble
                    val step = jsonObject.getAsJsonPrimitive("step").asDouble

                    FloatOption(
                        displayName,
                        tooltip,
                        structure.needsRestart,
                        { value },
                        { newValue -> value = newValue },
                        min, max, step,
                        FloatOption.STANDARD_FORMATTER
                    )
                }
                "int" -> {
                    var value = jsonObject.getAsJsonPrimitive("value").asInt
                    val min = jsonObject.getAsJsonPrimitive("min").asInt
                    val max = jsonObject.getAsJsonPrimitive("max").asInt
                    val step = jsonObject.getAsJsonPrimitive("step").asInt

                    IntOption(
                        displayName,
                        tooltip,
                        structure.needsRestart,
                        { value },
                        { newValue -> value = newValue },
                        min, max, step,
                        IntOption.STANDARD_FORMATTER
                    )
                }
                "enum" -> {
                    var selected = jsonObject.getAsJsonPrimitive("selected").asInt

                    // One translated component per value, in the order the schema lists them: the
                    // widget shows one of these rather than a translated value name per frame.
                    val values = structure.variants.mapIndexed { index, display ->
                        OptionText.value(structure.variantKeys.getOrNull(index), display)
                    }.toTypedArray()

                    TextEnumOption  (
                        displayName,
                        tooltip,
                        structure.needsRestart,
                        { selected },
                        { newValue -> selected = newValue },
                        values
                    )
                }
                else -> throw JsonParseException("Unexpected value: $type")
            }

            // What the renderer calls it, which is what the settings are sent back under.
            option.setting = name
            return option
        }

        override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): List<Option<*>> {
            if (json !is JsonObject) {
                throw JsonParseException("Expected JsonObject, got ${json::class.java.simpleName}")
            }

            val options = ArrayList<Option<*>>()
            for ((key, value) in json.entrySet()) {
                try {
                    options.add(deserializeOption(value.asJsonObject, key))
                } catch (e: IllegalStateException) {
                    throw JsonParseException(e)
                }
            }
            return options
        }

        override fun serialize(src: List<Option<*>>, typeOfSrc: Type, context: JsonSerializationContext): JsonElement {
            val root = JsonObject()

            for (option in src) {
                // The renderer's name, not the translated one: this document is the renderer's
                // config, and `wgpu_mc.option.vsync` is not a key it has ever heard of.
                root.add(option.setting ?: option.name.string, serializeOption(option))
            }

            return root
        }

        private fun serializeOption(option: Option<*>): JsonObject {
            val root = JsonObject()
            when (option) {
                is BoolOption -> {
                    root.addProperty("type", "bool")
                    root.addProperty("value", option.get())
                }
                is IntOption -> {
                    root.addProperty("type", "int")
                    root.addProperty("value", option.get())
                    root.addProperty("min", option.min)
                    root.addProperty("max", option.max)
                    root.addProperty("step", option.step)
                }
                is TextEnumOption -> {
                    root.addProperty("type", "enum")
                    root.addProperty("selected", option.get())
                }
                is FloatOption -> {
                    root.addProperty("type", "float")
                    root.addProperty("value", option.get())
                    root.addProperty("min", option.min)
                    root.addProperty("max", option.max)
                    root.addProperty("step", option.step)
                }
                is EnumOption<*> -> {
                    throw IllegalStateException("There should be no EnumOption here!")
                }
                else -> throw IllegalStateException("Unknown option type: ${option::class.java.simpleName}")
            }
            return root
        }
    }
}
