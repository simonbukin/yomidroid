package com.yomidroid.config

import android.content.Context
import android.content.SharedPreferences
import android.view.KeyEvent

/**
 * Actions that can be bound to hardware buttons.
 * Each action maps to an existing touch gesture in the app.
 */
enum class BindableAction(val displayName: String, val description: String) {
    SCAN("Scan", "Capture screen and run OCR"),
    DISMISS("Dismiss", "Close overlay / popup"),
    TOGGLE_CURSOR("Toggle Cursor", "Show/hide cursor FAB"),
    OPEN_APP("Open App", "Open Yomidroid main app"),
    MOVE_UP("Move Up", "Move cursor up"),
    MOVE_DOWN("Move Down", "Move cursor down"),
    MOVE_LEFT("Move Left", "Move cursor left"),
    MOVE_RIGHT("Move Right", "Move cursor right"),
    BLOCK_UP("Block Up", "Jump to OCR block above"),
    BLOCK_DOWN("Block Down", "Jump to OCR block below"),
    BLOCK_LEFT("Block Left", "Jump to OCR block left"),
    BLOCK_RIGHT("Block Right", "Jump to OCR block right"),
    SCROLL_UP("Scroll Up", "Scroll popup up"),
    SCROLL_DOWN("Scroll Down", "Scroll popup down"),
    LOCK_POPUP("Lock Popup", "Lock focus into popup for button navigation");
}

/**
 * Configuration for hardware input bindings.
 *
 * Uses a layer key system: bindings only activate while the layer key is held.
 * This prevents conflicts with game controls - the layer key acts as a modifier.
 *
 * @param enabled Master toggle for hardware input
 * @param layerKeyCode The modifier key that activates the binding layer (0 = no layer, direct binding)
 * @param bindings Map of action -> keyCode (consumed only when layer key is held, or directly if no layer)
 * @param cursorSpeed Pixels per frame for D-pad cursor movement
 * @param cursorAcceleration Whether cursor accelerates when held
 * @param scrollSpeed Pixels per scroll step for popup scrolling
 */
data class InputConfig(
    val enabled: Boolean = true,
    val layerKeyCode: Int = KeyEvent.KEYCODE_BUTTON_START,
    val bindings: Map<BindableAction, Int> = DEFAULT_BINDINGS,
    val cursorSpeed: Float = 8f,
    val cursorAcceleration: Boolean = true,
    val scrollSpeed: Float = 60f
) {
    companion object {
        val DEFAULT_BINDINGS: Map<BindableAction, Int> = mapOf(
            BindableAction.SCAN to KeyEvent.KEYCODE_BUTTON_Y,
            BindableAction.DISMISS to KeyEvent.KEYCODE_BUTTON_B,
            BindableAction.MOVE_UP to KeyEvent.KEYCODE_DPAD_UP,
            BindableAction.MOVE_DOWN to KeyEvent.KEYCODE_DPAD_DOWN,
            BindableAction.MOVE_LEFT to KeyEvent.KEYCODE_DPAD_LEFT,
            BindableAction.MOVE_RIGHT to KeyEvent.KEYCODE_DPAD_RIGHT,
            BindableAction.SCROLL_UP to KeyEvent.KEYCODE_BUTTON_L1,
            BindableAction.SCROLL_DOWN to KeyEvent.KEYCODE_BUTTON_R1,
            BindableAction.LOCK_POPUP to KeyEvent.KEYCODE_BUTTON_A,
        )

        /**
         * Keys that must never be consumed (system navigation).
         */
        val NEVER_CONSUME = setOf(
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_APP_SWITCH
        )
    }
}

/**
 * Manages InputConfig persistence via SharedPreferences.
 */
class InputConfigManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME, Context.MODE_PRIVATE
    )

    companion object {
        private const val PREFS_NAME = "input_config"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_LAYER_KEY = "layer_key"
        private const val KEY_CURSOR_SPEED = "cursor_speed"
        private const val KEY_CURSOR_ACCELERATION = "cursor_acceleration"
        private const val KEY_SCROLL_SPEED = "scroll_speed"
        private const val KEY_BINDING_PREFIX = "binding_"
    }

    fun getConfig(): InputConfig {
        // Fresh install: no prefs saved yet → return defaults
        if (!prefs.contains(KEY_ENABLED)) return InputConfig()

        val bindings = mutableMapOf<BindableAction, Int>()
        for (action in BindableAction.entries) {
            val keyCode = prefs.getInt("$KEY_BINDING_PREFIX${action.name}", 0)
            if (keyCode != 0) {
                bindings[action] = keyCode
            }
        }

        return InputConfig(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            layerKeyCode = prefs.getInt(KEY_LAYER_KEY, 0),
            bindings = bindings,
            cursorSpeed = prefs.getFloat(KEY_CURSOR_SPEED, 8f),
            cursorAcceleration = prefs.getBoolean(KEY_CURSOR_ACCELERATION, true),
            scrollSpeed = prefs.getFloat(KEY_SCROLL_SPEED, 60f)
        )
    }

    fun saveConfig(config: InputConfig) {
        val editor = prefs.edit()
            .putBoolean(KEY_ENABLED, config.enabled)
            .putInt(KEY_LAYER_KEY, config.layerKeyCode)
            .putFloat(KEY_CURSOR_SPEED, config.cursorSpeed)
            .putBoolean(KEY_CURSOR_ACCELERATION, config.cursorAcceleration)
            .putFloat(KEY_SCROLL_SPEED, config.scrollSpeed)

        // Save all bindings (including defaults for unset ones)
        for (action in BindableAction.entries) {
            val keyCode = config.bindings[action] ?: 0
            editor.putInt("$KEY_BINDING_PREFIX${action.name}", keyCode)
        }

        editor.apply()
    }

    fun resetToDefaults() {
        prefs.edit().clear().apply()
    }
}

/**
 * Human-readable names for Android key codes.
 */
fun keyCodeToDisplayName(keyCode: Int): String = when (keyCode) {
    0 -> "Unbound"
    KeyEvent.KEYCODE_BUTTON_A -> "A"
    KeyEvent.KEYCODE_BUTTON_B -> "B"
    KeyEvent.KEYCODE_BUTTON_X -> "X"
    KeyEvent.KEYCODE_BUTTON_Y -> "Y"
    KeyEvent.KEYCODE_BUTTON_L1 -> "L1"
    KeyEvent.KEYCODE_BUTTON_R1 -> "R1"
    KeyEvent.KEYCODE_BUTTON_L2 -> "L2"
    KeyEvent.KEYCODE_BUTTON_R2 -> "R2"
    KeyEvent.KEYCODE_BUTTON_THUMBL -> "L3"
    KeyEvent.KEYCODE_BUTTON_THUMBR -> "R3"
    KeyEvent.KEYCODE_BUTTON_START -> "Start"
    KeyEvent.KEYCODE_BUTTON_SELECT -> "Select"
    KeyEvent.KEYCODE_BUTTON_MODE -> "Mode"
    KeyEvent.KEYCODE_DPAD_UP -> "D-Pad Up"
    KeyEvent.KEYCODE_DPAD_DOWN -> "D-Pad Down"
    KeyEvent.KEYCODE_DPAD_LEFT -> "D-Pad Left"
    KeyEvent.KEYCODE_DPAD_RIGHT -> "D-Pad Right"
    KeyEvent.KEYCODE_DPAD_CENTER -> "D-Pad Center"
    else -> KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_").lowercase()
        .replaceFirstChar { it.uppercase() }
}
