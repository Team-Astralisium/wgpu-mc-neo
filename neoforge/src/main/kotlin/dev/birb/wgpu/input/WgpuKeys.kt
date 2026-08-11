package dev.birb.wgpu.input

import dev.birb.wgpu.WgpuMcMod
import org.lwjgl.glfw.GLFW

object WgpuKeys {
    const val WGPU_LSHIFT = 118
    const val WGPU_RSHIFT = 139
    const val WGPU_LCONTROL = 117
    const val WGPU_RCONTROL = 138
    const val WGPU_F3 = 39
    const val WGPU_F4 = 40
    const val WGPU_F5 = 41
    const val WGPU_BACKSPACE = 74
    const val WGPU_TAB = 146
    const val WGPU_ESCAPE = 36
    const val WGPU_LEFT = 70
    const val WGPU_UP = 71
    const val WGPU_RIGHT = 72
    const val WGPU_DOWN = 73
    const val WGPU_HOME = 65
    const val WGPU_DELETE = 66
    const val WGPU_END = 67
    const val WGPU_ENTER = 75
    private const val WGPU_SPACE = 76

    const val WGPU_SHIFT = 0b100
    const val WGPU_CONTROL = 0b100 shl 3
    const val WGPU_ALT = 0b100 shl 6
    const val WGPU_LOGO = 0b100 shl 9

    @JvmStatic
    fun convertKeyCode(code: Int): Int {
        val converted: Int = when {
            code in 0..9 -> code + 48
            code in 10..35 -> code + 55
            code == WGPU_LSHIFT -> GLFW.GLFW_KEY_LEFT_SHIFT
            code == WGPU_RSHIFT -> GLFW.GLFW_KEY_RIGHT_SHIFT
            code == WGPU_LCONTROL -> GLFW.GLFW_KEY_LEFT_CONTROL
            code == WGPU_RCONTROL -> GLFW.GLFW_KEY_RIGHT_CONTROL
            code == WGPU_F3 -> GLFW.GLFW_KEY_F3
            code == WGPU_F4 -> GLFW.GLFW_KEY_F4
            code == WGPU_F5 -> GLFW.GLFW_KEY_F5
            code == WGPU_BACKSPACE -> GLFW.GLFW_KEY_BACKSPACE
            code == WGPU_TAB -> GLFW.GLFW_KEY_TAB
            code == WGPU_ESCAPE -> GLFW.GLFW_KEY_ESCAPE
            code == WGPU_LEFT -> GLFW.GLFW_KEY_LEFT
            code == WGPU_UP -> GLFW.GLFW_KEY_UP
            code == WGPU_RIGHT -> GLFW.GLFW_KEY_RIGHT
            code == WGPU_DOWN -> GLFW.GLFW_KEY_DOWN
            code == WGPU_HOME -> GLFW.GLFW_KEY_HOME
            code == WGPU_END -> GLFW.GLFW_KEY_END
            code == WGPU_DELETE -> GLFW.GLFW_KEY_DELETE
            code == WGPU_ENTER -> GLFW.GLFW_KEY_ENTER
            code == WGPU_SPACE -> GLFW.GLFW_KEY_SPACE
            else -> -1
        }

        if (converted == -1) {
            WgpuMcMod.LOGGER.error("Couldn't convert winit keycode $code to GLFW")
        }
        return converted
    }

    @JvmStatic
    fun convertModifiers(mods: Int): Int {
        if (mods == 0) return 0

        var output = 0
        if ((mods and WGPU_SHIFT) != 0) output = output or GLFW.GLFW_MOD_SHIFT
        if ((mods and WGPU_CONTROL) != 0) output = output or GLFW.GLFW_MOD_CONTROL
        if ((mods and WGPU_ALT) != 0) output = output or GLFW.GLFW_MOD_ALT
        if ((mods and WGPU_LOGO) != 0) output = output or GLFW.GLFW_MOD_SUPER

        return output
    }
}
