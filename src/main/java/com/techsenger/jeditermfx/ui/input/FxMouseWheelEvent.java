/*
 * Patched copy of JediTermFX 1.1.0 FxMouseWheelEvent (com.techsenger.jeditermfx:jeditermfx-ui), shadowing the library
 * class from the classpath. Only change: the wheel direction (look for "Fabric Manager patch"). Drop this file once
 * upstream fixes it.
 */
package com.techsenger.jeditermfx.ui.input;

import javafx.scene.input.ScrollEvent;
import com.techsenger.jeditermfx.core.input.MouseWheelEvent;
import org.jetbrains.annotations.NotNull;
import com.techsenger.jeditermfx.core.emulator.mouse.MouseButtonCodes;
import com.techsenger.jeditermfx.core.emulator.mouse.MouseButtonModifierFlags;

public final class FxMouseWheelEvent extends MouseWheelEvent {

    private static int createButtonCode(@NotNull ScrollEvent fxMouseEvent) {
        // Fabric Manager patch: the library names X11 button 4 (wheel up) SCROLLDOWN and button 5 SCROLLUP. JavaFX
        // deltaY > 0 is the wheel turned up, so it is button 4; upstream sent the reverse, and apps scrolled backwards.
        if (fxMouseEvent.getDeltaY() > 0) {
            return MouseButtonCodes.SCROLLDOWN;
        } else {
            return MouseButtonCodes.SCROLLUP;
        }
    }

    private static int getModifierKeys(@NotNull ScrollEvent fxMouseEvent) {
        int modifier = 0;
        if (fxMouseEvent.isControlDown()) {
            modifier |= MouseButtonModifierFlags.MOUSE_BUTTON_CTRL_FLAG;
        }
        if (fxMouseEvent.isShiftDown()) {
            modifier |= MouseButtonModifierFlags.MOUSE_BUTTON_SHIFT_FLAG;
        }
        if (fxMouseEvent.isMetaDown()) {
            modifier |= MouseButtonModifierFlags.MOUSE_BUTTON_META_FLAG;
        }
        return modifier;
    }

    private final ScrollEvent myFxMouseWheelEvent;

    public FxMouseWheelEvent(@NotNull ScrollEvent fxMouseWheelEvent) {
        super(createButtonCode(fxMouseWheelEvent), getModifierKeys(fxMouseWheelEvent));
        myFxMouseWheelEvent = fxMouseWheelEvent;
    }

    @Override
    public String toString() {
        return myFxMouseWheelEvent.toString();
    }
}
