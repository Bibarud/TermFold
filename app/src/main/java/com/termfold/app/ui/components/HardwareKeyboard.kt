package com.termfold.app.ui.components

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent

/**
 * Whether the user is typing on a physical keyboard right now.
 *
 * Asking Android whether a keyboard is *connected* does not work: tablets with a keyboard-cover
 * port (Lenovo's `tn_keyboard`, for one) report a permanently attached external keyboard, and the
 * configuration says `qwerty` with the keyboard visible, even with nothing plugged in. Acting on
 * that hid the on-screen keyboard every time the user tapped a text field.
 *
 * So what counts is use, not presence: a key press from a real (non-virtual) alphabetic keyboard
 * means the user is typing on hardware, and a touch on the screen means they have gone back to
 * touch. [MainActivity] feeds both in from its dispatch methods.
 */
object PhysicalKeyboard {

    @Volatile
    var inUse: Boolean = false
        private set

    /** Returns true when this event came from a physical keyboard (and records that). */
    fun onKeyEvent(event: KeyEvent): Boolean {
        val device = event.device ?: return false
        val physical = !device.isVirtual &&
            event.deviceId != android.view.KeyCharacterMap.VIRTUAL_KEYBOARD &&
            device.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC &&
            (event.source and InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD &&
            // Printable keys and the editing keys a person types with; volume and power keys come
            // from "keyboards" too, and must not count.
            (event.isPrintingKey || event.keyCode in TYPING_KEYS)
        if (physical) inUse = true
        return physical
    }

    fun onTouch(event: MotionEvent) {
        if (event.actionMasked == MotionEvent.ACTION_DOWN &&
            event.getToolType(0) != MotionEvent.TOOL_TYPE_MOUSE
        ) {
            inUse = false
        }
    }

    private val TYPING_KEYS = setOf(
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL,
        KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_SPACE,
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
    )
}
