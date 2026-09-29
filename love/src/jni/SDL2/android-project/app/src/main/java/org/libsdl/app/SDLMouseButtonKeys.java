package org.libsdl.app;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.hardware.input.InputManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.SparseIntArray;
import android.util.SparseLongArray;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

/**
 * love2d-mod: a mouse's back button is its right button.
 *
 * Some Android builds, the Compy's among them, report a mouse's right
 * button as the back button. On the Compy its state comes only in
 * hover events (enter and move), 0x8 at the press and 0x0 at the
 * release, with no ACTION_DOWN, ACTION_UP or ACTION_BUTTON_PRESS.
 * Every mouse event passes through onMouseEvent(), which sends SDL
 * the back button's press and release from the change in its button
 * state, whatever the event's action.
 *
 * Android also sends KEYCODE_BACK for the press, with repeats while
 * the button is held. On the Compy that key has SOURCE_MOUSE, scan
 * code 0 and the device id of the mouse's motion events, and is
 * dropped for its source. The keyboard's Escape is KEYCODE_BACK too,
 * from the same composite device, with a keyboard source and a scan
 * code. For a build that sends the mouse's key without SOURCE_MOUSE,
 * a key without a scan code is paired with the mouse button that
 * comes with it: a key whose button went down on the same device
 * within PAIR_MS of it belongs to that button, and the key is
 * dropped with its release. The key may come before the button, so
 * such a key is held for up to PAIR_MS for its button to arrive,
 * then passed on. Repeats follow what happened to the key's first
 * press. A key with a scan code came from a physical key and always
 * passes.
 *
 * The key is filtered before the input method too, which would
 * otherwise take a Back while text input is shown.
 *
 * Debuggable builds log every step under the tag SDL.
 *
 * All of it runs on the UI thread, where key and pointer events
 * come.
 */
final class SDLMouseButtonKeys {
    private static final String TAG = "SDL";

    /** How far apart in time a key and its mouse button may come */
    static final long PAIR_MS = 100;

    private static final Handler sHandler = new Handler(Looper.getMainLooper());

    /** Whether to log: set once, from the app's debuggable flag */
    private static Boolean sLog;

    /** The mouse buttons down in the last event from each device */
    private static final SparseIntArray sButtons = new SparseIntArray();
    /** When each button last went down, by device */
    private static final SparseLongArray sBackAt = new SparseLongArray();
    private static final SparseLongArray sForwardAt = new SparseLongArray();
    /** Buttons whose key went down and was dropped, by the key's device */
    private static final SparseIntArray sDropped = new SparseIntArray();
    /** A key down held for its mouse button, and what passes it on */
    private static KeyEvent sHeld;
    private static final Runnable sPassHeld = new Runnable() {
        @Override
        public void run() {
            passHeld();
        }
    };
    /** A held key's press passed to SDL, whose release goes the same way */
    private static KeyEvent sPassed;
    /** Whether SDL has the back button as held, and where the mouse was */
    private static boolean sBackSent;
    private static float sLastX;
    private static float sLastY;
    private static boolean sLastRelative;

    private SDLMouseButtonKeys() {
    }

    /** The mouse button a key code stands for, or 0 */
    private static int buttonFor(int keyCode) {
        switch (keyCode) {
        case KeyEvent.KEYCODE_BACK:
            return MotionEvent.BUTTON_BACK;
        case KeyEvent.KEYCODE_FORWARD:
            return MotionEvent.BUTTON_FORWARD;
        default:
            return 0;
        }
    }

    private static boolean near(long a, long b) {
        return Math.abs(a - b) <= PAIR_MS;
    }

    /** Whether a mouse button went down on the key's device near its time */
    private static boolean pairs(KeyEvent event, int button) {
        SparseLongArray times = button == MotionEvent.BUTTON_BACK ? sBackAt : sForwardAt;
        int i = times.indexOfKey(event.getDeviceId());
        return i >= 0 && near(event.getEventTime(), times.valueAt(i));
    }

    /** Whether a physical key made the key: then it is never a mouse's */
    private static boolean fromKey(KeyEvent event) {
        return event.getScanCode() != 0;
    }

    private static boolean isHeld(KeyEvent event) {
        return sHeld != null && sHeld.getKeyCode() == event.getKeyCode()
            && sHeld.getDeviceId() == event.getDeviceId();
    }

    private static boolean dropped(KeyEvent event, int button) {
        return (sDropped.get(event.getDeviceId()) & button) != 0;
    }

    private static void setDropped(int device, int button, boolean on) {
        int bits = sDropped.get(device);
        sDropped.put(device, on ? bits | button : bits & ~button);
    }

    private static boolean logging() {
        if (sLog == null) {
            Context context = SDL.getContext();
            if (context == null) {
                // decided once the app's context exists
                return false;
            }
            sLog = (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        }
        return sLog;
    }

    private static void log(String what, KeyEvent event) {
        if (!logging()) {
            return;
        }
        InputDevice device = InputDevice.getDevice(event.getDeviceId());
        Log.v(TAG, what + ": key " + event.getKeyCode()
            + " action " + event.getAction()
            + " repeat " + event.getRepeatCount()
            + " scan " + event.getScanCode()
            + " source 0x" + Integer.toHexString(event.getSource())
            + " device " + event.getDeviceId()
            + " device sources 0x" + (device == null ? "none" : Integer.toHexString(device.getSources()))
            + " flags 0x" + Integer.toHexString(event.getFlags())
            + " time " + event.getEventTime());
    }

    /**
     * Called for a key with the mouse source as it is dropped. Its
     * release also releases the back button, should the button's own
     * release have been missed.
     */
    static void mouseKeyDropped(KeyEvent event) {
        log("dropped, mouse source", event);
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK
                && event.getAction() == KeyEvent.ACTION_UP) {
            releaseBack(event.getDeviceId());
        }
    }

    /** Logs a Back or Forward key where it passes, for the record */
    static void trace(String where, KeyEvent event) {
        // DPAD_CENTER, SDL's 'select', for where a left click's key goes
        if (buttonFor(event.getKeyCode()) != 0
                || event.getKeyCode() == KeyEvent.KEYCODE_DPAD_CENTER) {
            log(where, event);
        }
    }

    /**
     * Called for every key before SDL takes it, and before the input
     * method takes it while text input is shown.
     * @return true when the key is a mouse button's, or held for now:
     *         the caller stops there
     */
    static boolean consume(KeyEvent event) {
        int button = buttonFor(event.getKeyCode());
        if (button == 0) {
            return false;
        }
        if ((event.getSource() & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE) {
            // a mouse's own key, as SDLActivity drops it after the input method
            mouseKeyDropped(event);
            return true;
        }
        switch (event.getAction()) {
        case KeyEvent.ACTION_DOWN:
            return onDown(event, button);
        case KeyEvent.ACTION_UP:
            return onUp(event, button);
        default:
            return false;
        }
    }

    private static boolean onDown(KeyEvent event, int button) {
        if (fromKey(event)) {
            log("passed, from a key", event);
            return false;
        }
        if (event.getRepeatCount() > 0) {
            // a repeat goes where its first press went
            boolean stop = dropped(event, button) || isHeld(event);
            log(stop ? "repeat dropped" : "repeat passed", event);
            return stop;
        }
        if (pairs(event, button)) {
            setDropped(event.getDeviceId(), button, true);
            log("dropped, its button came first", event);
            return true;
        }
        passHeld();
        sHeld = event;
        sHandler.postDelayed(sPassHeld, PAIR_MS);
        log("held for its button", event);
        return true;
    }

    private static boolean onUp(KeyEvent event, int button) {
        if (fromKey(event)) {
            log("passed, from a key", event);
            return false;
        }
        if (dropped(event, button)) {
            setDropped(event.getDeviceId(), button, false);
            if (isHeld(event)) {
                // a later press of the same key, now without its release
                sHandler.removeCallbacks(sPassHeld);
                sHeld = null;
            }
            log("dropped, its button's", event);
            if (button == MotionEvent.BUTTON_BACK) {
                releaseBack(event.getDeviceId());
            }
            return true;
        }
        if (isHeld(event)) {
            // released before its button came: it was a key after all
            passHeld();
        }
        if (sPassed != null && sPassed.getKeyCode() == event.getKeyCode()
                && sPassed.getDeviceId() == event.getDeviceId()) {
            // its press went straight to SDL, so its release does too
            sPassed = null;
            log("passed, after its press", event);
            SDLActivity.onNativeKeyUp(event.getKeyCode());
            if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && SDLActivity.mTextEdit != null
                    && SDLActivity.mTextEdit.getVisibility() == android.view.View.VISIBLE) {
                // as DummyEdit.onKeyPreIme does for a keyboard's Back
                SDLActivity.onNativeKeyboardFocusLost();
            }
            return true;
        }
        log("passed", event);
        return false;
    }

    /** The held key down goes on to SDL */
    private static void passHeld() {
        sHandler.removeCallbacks(sPassHeld);
        KeyEvent held = sHeld;
        sHeld = null;
        if (held != null) {
            log("passed, no button came", held);
            sPassed = held;
            SDLActivity.onNativeKeyDown(held.getKeyCode());
        }
    }

    /**
     * Called with every mouse event before SDL takes it, with the
     * position SDL gets for it. Sends SDL the back button's press and
     * release; a back or forward button that went down drops a held
     * key it belongs to, and is remembered for a key that comes after
     * it.
     */
    static void onMouseEvent(MotionEvent event, int buttons, float x, float y, boolean relative) {
        watchDevices();
        int device = event.getDeviceId();
        int last = sButtons.get(device);
        sButtons.put(device, buttons);
        int action = event.getActionMasked();
        int pressed = buttons & ~last;
        int released = last & ~buttons;
        if (logging() && (pressed != 0 || released != 0
                || (action != MotionEvent.ACTION_MOVE && action != MotionEvent.ACTION_HOVER_MOVE))) {
            Log.v(TAG, "mouse action " + action
                + " buttons 0x" + Integer.toHexString(buttons)
                + " action button 0x" + Integer.toHexString(
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? event.getActionButton() : 0)
                + " source 0x" + Integer.toHexString(event.getSource())
                + " device " + device
                + " time " + event.getEventTime());
        }
        if (!relative) {
            sLastX = x;
            sLastY = y;
        }
        sLastRelative = relative;

        long at = event.getEventTime();
        if ((pressed & MotionEvent.BUTTON_BACK) != 0) {
            sBackAt.put(device, at);
            if (!sBackSent) {
                sBackSent = true;
                SDLActivity.onNativeMouse(buttons, MotionEvent.ACTION_DOWN, x, y, relative);
            }
        }
        if ((pressed & MotionEvent.BUTTON_FORWARD) != 0) {
            sForwardAt.put(device, at);
        }
        if ((released & MotionEvent.BUTTON_BACK) != 0 && sBackSent) {
            sBackSent = false;
            SDLActivity.onNativeMouse(buttons, MotionEvent.ACTION_UP, x, y, relative);
        }
        if (sHeld == null) {
            return;
        }
        int button = buttonFor(sHeld.getKeyCode());
        if ((pressed & button) != 0 && pairs(sHeld, button)) {
            sHandler.removeCallbacks(sPassHeld);
            log("dropped, its button came", sHeld);
            sHeld = null;
            setDropped(device, button, true);
        }
    }

    /**
     * The mouse key's release came while SDL still holds the back
     * button for its device: the button's own release was missed, so
     * it is released here.
     */
    private static void releaseBack(int device) {
        int buttons = sButtons.get(device);
        if (!sBackSent || (buttons & MotionEvent.BUTTON_BACK) == 0) {
            return;
        }
        sButtons.put(device, buttons & ~MotionEvent.BUTTON_BACK);
        sBackSent = false;
        if (logging()) {
            Log.v(TAG, "mouse back button released with its key, device " + device);
        }
        SDLActivity.onNativeMouse(buttons & ~MotionEvent.BUTTON_BACK, MotionEvent.ACTION_UP,
            sLastRelative ? 0 : sLastX, sLastRelative ? 0 : sLastY, sLastRelative);
    }

    /** Releases in SDL every button of a device that went away */
    private static final InputManager.InputDeviceListener sDevices =
            new InputManager.InputDeviceListener() {
        @Override
        public void onInputDeviceAdded(int deviceId) {
        }

        @Override
        public void onInputDeviceChanged(int deviceId) {
        }

        @Override
        public void onInputDeviceRemoved(int deviceId) {
            int buttons = sButtons.get(deviceId);
            sButtons.delete(deviceId);
            sDropped.delete(deviceId);
            sBackAt.delete(deviceId);
            sForwardAt.delete(deviceId);
            if (buttons == 0) {
                return;
            }
            int held = 0;
            for (int i = 0; i < sButtons.size(); i++) {
                held |= sButtons.valueAt(i);
            }
            if ((held & MotionEvent.BUTTON_BACK) == 0) {
                sBackSent = false;
            }
            if (logging()) {
                Log.v(TAG, "mouse device " + deviceId + " removed with buttons 0x"
                    + Integer.toHexString(buttons));
            }
            SDLActivity.onNativeMouse(held, MotionEvent.ACTION_UP,
                sLastRelative ? 0 : sLastX, sLastRelative ? 0 : sLastY, sLastRelative);
        }
    };
    private static boolean sWatching;

    private static void watchDevices() {
        if (sWatching) {
            return;
        }
        Context context = SDL.getContext();
        if (context == null) {
            return;
        }
        InputManager manager = (InputManager) context.getSystemService(Context.INPUT_SERVICE);
        if (manager != null) {
            manager.registerInputDeviceListener(sDevices, sHandler);
            sWatching = true;
        }
    }

    /**
     * Forgets every button when the activity pauses or loses focus,
     * whose releases then go elsewhere, and releases them in SDL. A
     * held key is dropped. Keys already dropped stay dropped, so the
     * repeats and release of a mouse key held through the pause stay
     * away from SDL.
     */
    static void reset() {
        sHandler.removeCallbacks(sPassHeld);
        sHeld = null;
        int held = 0;
        for (int i = 0; i < sButtons.size(); i++) {
            held |= sButtons.valueAt(i);
        }
        sButtons.clear();
        sBackAt.clear();
        sForwardAt.clear();
        sPassed = null;
        if (sBackSent || held != 0) {
            sBackSent = false;
            if (logging()) {
                Log.v(TAG, "mouse buttons released on reset: 0x" + Integer.toHexString(held));
            }
            // SDL releases every button it holds
            SDLActivity.onNativeMouse(0, MotionEvent.ACTION_UP,
                sLastRelative ? 0 : sLastX, sLastRelative ? 0 : sLastY, sLastRelative);
        }
    }
}
