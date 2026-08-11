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
    private var value: T = getter.get()

    fun get(): T = value

    fun set(value: T) {
        this.value = value
    }

    fun isChanged(): Boolean = value != getter.get()

    fun apply() {
        if (isChanged()) setter.accept(value)
    }

    fun undo() {
        value = getter.get()
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
    abstract class Builder<B : Builder<B, T>, T> {
        protected var name: Component? = null
        protected var tooltip: Component? = null
        protected var requiresRestart: Boolean = false
        protected var getter: Supplier<T>? = null
        protected var setter: Consumer<T>? = null

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

            return when (type) {
                "bool" -> {
                    var value = jsonObject.getAsJsonPrimitive("value").asBoolean
                    BoolOption(
                        Component.literal(name),
                        Component.literal(structure.desc ?: ""),
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
                        Component.literal(name),
                        Component.literal(structure.desc ?: ""),
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
                        Component.literal(name),
                        Component.literal(structure.desc ?: ""),
                        structure.needsRestart,
                        { value },
                        { newValue -> value = newValue },
                        min, max, step,
                        IntOption.STANDARD_FORMATTER
                    )
                }
                "enum" -> {
                    var selected = jsonObject.getAsJsonPrimitive("selected").asInt
                    TextEnumOption  (
                        Component.literal(name),
                        Component.literal(structure.desc ?: ""),
                        structure.needsRestart,
                        { selected },
                        { newValue -> selected = newValue },
                        structure.variants
                    )
                }
                else -> throw JsonParseException("Unexpected value: $type")
            }
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
                root.add(option.name.string, serializeOption(option))
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
