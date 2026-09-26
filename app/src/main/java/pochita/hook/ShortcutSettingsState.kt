package pochita.hook

import android.content.Context
import pochita.model.ActionType
import pochita.model.KeyConfig

/**
 * 快捷键运行时状态与热路径内存缓存
 *
 * 维护纳秒级无锁只读键码，供画板高频按键事件秒级匹配分发；
 * 管理就地行内录制状态及监听器回调。
 */
object ShortcutSettingsState {

    @Volatile
    var keyDragCanvas: Int = ActionType.DRAG_CANVAS_HOLD.defaultKeyCode
        private set

    @Volatile
    var keyToggleEraser: Int = ActionType.TOGGLE_ERASER_HOLD.defaultKeyCode
        private set

    @Volatile
    var keyLasso: Int = ActionType.SELECT_LASSO.defaultKeyCode
        private set

    @Volatile
    var keyPen: Int = ActionType.SELECT_PEN.defaultKeyCode
        private set

    @Volatile
    var keyEraser: Int = ActionType.SELECT_ERASER.defaultKeyCode
        private set

    /**
     * 当前正在就地录制快捷键的操作类型；null 表示无录制进行中
     */
    @Volatile
    var recordingAction: ActionType? = null
        private set

    /**
     * 录制状态变更与按键更新监听器（供就地行内视图刷新使用）
     */
    @Volatile
    var onStateChangedListener: (() -> Unit)? = null

    private var isInitialized = false

    @Synchronized
    fun initIfNeeded(context: Context) {
        if (!isInitialized) {
            reload(context)
            isInitialized = true
        }
    }

    @Synchronized
    fun reload(context: Context) {
        val config = KeyConfig(context)
        keyDragCanvas = config.keyDragCanvas
        keyToggleEraser = config.keyToggleEraser
        keyLasso = config.keyLasso
        keyPen = config.keyPen
        keyEraser = config.keyEraser
        onStateChangedListener?.invoke()
    }

    fun getKeyCode(action: ActionType): Int {
        return when (action) {
            ActionType.DRAG_CANVAS_HOLD -> keyDragCanvas
            ActionType.TOGGLE_ERASER_HOLD -> keyToggleEraser
            ActionType.SELECT_LASSO -> keyLasso
            ActionType.SELECT_PEN -> keyPen
            ActionType.SELECT_ERASER -> keyEraser
        }
    }

    @Synchronized
    fun setKeyCode(context: Context, action: ActionType, keyCode: Int) {
        val config = KeyConfig(context)
        config.setKeyCode(action, keyCode)
        reload(context)
    }

    @Synchronized
    fun startRecording(action: ActionType) {
        recordingAction = action
        onStateChangedListener?.invoke()
    }

    @Synchronized
    fun stopRecording() {
        if (recordingAction != null) {
            recordingAction = null
            onStateChangedListener?.invoke()
        }
    }
}
