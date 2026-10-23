package com.liam.homescreen;

import android.view.KeyEvent;

/**
 * One keyboard / remote map for every screen:
 * <ul>
 *   <li>Esc · Backspace · B → Back (leave / pop)</li>
 *   <li>Enter → OK</li>
 *   <li>Arrows · WASD · IJKL → D-pad</li>
 *   <li>M → Menu / options</li>
 *   <li>Space → OK on menus; Player treats Space as play/pause separately</li>
 * </ul>
 */
final class NavKeys {
    private NavKeys() {}

    /**
     * @param typing true while editing search text (letters stay literal; Esc still = Back).
     */
    static int navCode(int keyCode, boolean typing) {
        // Always-on aliases (even while typing).
        switch (keyCode) {
            case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_BUTTON_B:
                return KeyEvent.KEYCODE_BACK;
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_BUTTON_SELECT:
                return KeyEvent.KEYCODE_DPAD_CENTER;
            case KeyEvent.KEYCODE_F2:
            case KeyEvent.KEYCODE_BUTTON_START:
                return KeyEvent.KEYCODE_MENU;
            default:
                break;
        }
        if (typing) {
            // Backspace edits text while searching; Esc already mapped to Back above.
            return keyCode;
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_A:
            case KeyEvent.KEYCODE_J:
                return KeyEvent.KEYCODE_DPAD_LEFT;
            case KeyEvent.KEYCODE_D:
            case KeyEvent.KEYCODE_L:
                return KeyEvent.KEYCODE_DPAD_RIGHT;
            case KeyEvent.KEYCODE_W:
            case KeyEvent.KEYCODE_I:
                return KeyEvent.KEYCODE_DPAD_UP;
            case KeyEvent.KEYCODE_S:
            case KeyEvent.KEYCODE_K:
                return KeyEvent.KEYCODE_DPAD_DOWN;
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_SPACE:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                return KeyEvent.KEYCODE_DPAD_CENTER;
            case KeyEvent.KEYCODE_DEL:
            case KeyEvent.KEYCODE_FORWARD_DEL:
            case KeyEvent.KEYCODE_B:
                return KeyEvent.KEYCODE_BACK;
            case KeyEvent.KEYCODE_M:
                return KeyEvent.KEYCODE_MENU;
            case KeyEvent.KEYCODE_PAGE_UP:
                return KeyEvent.KEYCODE_CHANNEL_UP;
            case KeyEvent.KEYCODE_PAGE_DOWN:
                return KeyEvent.KEYCODE_CHANNEL_DOWN;
            default:
                return keyCode;
        }
    }

    static boolean isActivate(int keyCode) {
        int k = navCode(keyCode, false);
        return k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER;
    }

    static boolean isBack(int keyCode) {
        int k = navCode(keyCode, false);
        return k == KeyEvent.KEYCODE_BACK;
    }

    static boolean isDpad(int keyCode) {
        int k = navCode(keyCode, false);
        return k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT
                || k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN;
    }
}
