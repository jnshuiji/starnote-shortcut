package pochita.model

import android.content.Context
import android.content.SharedPreferences
import android.view.KeyEvent

/**
 * 快捷键配置持久化管理类
 *
 * 负责通过 SharedPreferences 存储各操作绑定的键码，
 * 支持快捷键冲突检测与友好的按键名称格式化。
 */
class KeyConfig(context: Context) {

    companion object {
        const val PREFS_NAME = "starnote_shortcut_config"
        private const val KEY_PREFIX = "shortcut_key_"

        /**
         * 将 Android 键码转换为友好的界面展示文本
         */
        fun getKeyDisplayName(keyCode: Int): String {
            if (keyCode <= 0) return ""

            return when (keyCode) {
                KeyEvent.KEYCODE_SPACE -> "Space"
                KeyEvent.KEYCODE_ENTER -> "Enter"
                KeyEvent.KEYCODE_TAB -> "Tab"
                KeyEvent.KEYCODE_DEL -> "Backspace"
                KeyEvent.KEYCODE_FORWARD_DEL -> "Delete"
                KeyEvent.KEYCODE_ESCAPE -> "Esc"
                KeyEvent.KEYCODE_GRAVE -> "`"
                KeyEvent.KEYCODE_MINUS -> "-"
                KeyEvent.KEYCODE_EQUALS -> "="
                KeyEvent.KEYCODE_LEFT_BRACKET -> "["
                KeyEvent.KEYCODE_RIGHT_BRACKET -> "]"
                KeyEvent.KEYCODE_BACKSLASH -> "\\"
                KeyEvent.KEYCODE_SEMICOLON -> ";"
                KeyEvent.KEYCODE_APOSTROPHE -> "'"
                KeyEvent.KEYCODE_COMMA -> ","
                KeyEvent.KEYCODE_PERIOD -> "."
                KeyEvent.KEYCODE_SLASH -> "/"
                in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> {
                    ('A' + (keyCode - KeyEvent.KEYCODE_A)).toString()
                }
                in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                    ('0' + (keyCode - KeyEvent.KEYCODE_0)).toString()
                }
                in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> {
                    "F${keyCode - KeyEvent.KEYCODE_F1 + 1}"
                }
                else -> {
                    val raw = KeyEvent.keyCodeToString(keyCode)
                    raw.removePrefix("KEYCODE_")
                }
            }
        }
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getKeyCode(action: ActionType): Int {
        return prefs.getInt(KEY_PREFIX + action.id, action.defaultKeyCode)
    }

    fun setKeyCode(action: ActionType, keyCode: Int) {
        val editor = prefs.edit()
        if (keyCode > 0) {
            // 冲突避让：若其他操作已绑定该按键，自动设空
            for (other in ActionType.entries) {
                if (other != action && getKeyCode(other) == keyCode) {
                    editor.putInt(KEY_PREFIX + other.id, 0)
                }
            }
        }
        editor.putInt(KEY_PREFIX + action.id, keyCode)
        editor.apply()
    }

    var keyToggleEraser: Int
        get() = getKeyCode(ActionType.TOGGLE_ERASER_HOLD)
        set(v) = setKeyCode(ActionType.TOGGLE_ERASER_HOLD, v)

    var keyLasso: Int
        get() = getKeyCode(ActionType.SELECT_LASSO)
        set(v) = setKeyCode(ActionType.SELECT_LASSO, v)

    var keyPen: Int
        get() = getKeyCode(ActionType.SELECT_PEN)
        set(v) = setKeyCode(ActionType.SELECT_PEN, v)

    var keyEraser: Int
        get() = getKeyCode(ActionType.SELECT_ERASER)
        set(v) = setKeyCode(ActionType.SELECT_ERASER, v)
}
