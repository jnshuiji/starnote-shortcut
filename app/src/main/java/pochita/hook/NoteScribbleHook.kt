package pochita.hook

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import pochita.model.ActionType
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap

/**
 * StarNote 画板 NoteScribbleActivity 内存调度控制器
 *
 * 1. 维护工具栏子 View 弱引用高速缓存，提供毫秒级直接 performClick 调度；
 * 2. 状态机管理「按切松回」按键操作，结合 50ms 硬件防抖机制确保流畅跟手；
 * 3. 避免重复点击已选中工具导致的次级弹窗干扰。
 */
object NoteScribbleHook {
    private const val TAG = "StarNoteScribbleHook"
    const val ACTION_COMMAND = "pochita.starnote.ACTION_COMMAND"
    const val EXTRA_CMD = "cmd"

    private var activeActivityRef: WeakReference<Activity>? = null

    // 缓存工具名称（如 "ERASER", "INK_PEN", "LASSO" 等）到对应的 View
    private val toolViewCache = ConcurrentHashMap<String, WeakReference<View>>()
    private var toolContainerRef: WeakReference<ViewGroup>? = null
    private var previousToolViewRef: WeakReference<View>? = null

    // 静态反射字段缓存，避免每次按键在主线程重复遍历类继承结构与 declaredFields
    private val classEnumFieldCache = ConcurrentHashMap<Class<*>, Field?>()
    private val classSelectedFieldCache = ConcurrentHashMap<Class<*>, Field?>()

    @Volatile
    private var isEraserHolding = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingRevertRunnable: Runnable? = null

    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_COMMAND) return
            val cmd = intent.getStringExtra(EXTRA_CMD) ?: return
            Log.d(TAG, "Received stylus command: $cmd")

            val activity = activeActivityRef?.get()
            if (activity == null || activity.isFinishing || activity.isDestroyed) {
                Log.w(TAG, "No active NoteScribbleActivity to handle command: $cmd")
                return
            }

            activity.runOnUiThread {
                handleCommand(activity, cmd)
            }
        }
    }

    fun onActivityResumed(activity: Activity) {
        if (activeActivityRef?.get() == activity) return
        activeActivityRef = WeakReference(activity)
        clearCaches()
        try {
            val filter = IntentFilter(ACTION_COMMAND)
            ContextCompat.registerReceiver(
                activity,
                commandReceiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED
            )
            Log.i(TAG, "Registered StarNote command receiver")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to register receiver in StarNote", t)
        }
    }

    fun onActivityPaused(activity: Activity) {
        if (activeActivityRef?.get() == activity) {
            activeActivityRef = null
        }
        clearCaches()
        try {
            activity.unregisterReceiver(commandReceiver)
            Log.i(TAG, "Unregistered StarNote command receiver")
        } catch (_: Throwable) {}
    }

    private fun clearCaches() {
        cancelPendingRevert()
        isEraserHolding = false
        toolViewCache.clear()
        toolContainerRef = null
        previousToolViewRef = null
    }

    private fun handleCommand(activity: Activity, cmd: String) {
        when (cmd) {
            "pen" -> selectPen(activity)
            "eraser" -> selectEraser(activity)
            "lasso" -> selectLasso(activity)
            "eraser_hold_down" -> onEraserHoldDown(activity)
            "eraser_hold_up" -> onEraserHoldUp(activity)
            else -> Log.w(TAG, "Unknown stylus command: $cmd")
        }
    }

    private fun findViewByName(activity: Activity, name: String): View? {
        val resId = activity.resources.getIdentifier(name, "id", activity.packageName)
        if (resId != 0) {
            val view = activity.findViewById<View>(resId)
            if (view != null) return view
        }
        val decor = activity.window?.decorView as? ViewGroup ?: return null
        return findViewRecursively(decor, name)
    }

    private fun findViewRecursively(parent: ViewGroup, targetName: String): View? {
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (child.id != View.NO_ID) {
                try {
                    val entry = child.resources.getResourceEntryName(child.id)
                    if (entry == targetName) return child
                } catch (_: Throwable) {}
            }
            if (child is ViewGroup) {
                val found = findViewRecursively(child, targetName)
                if (found != null) return found
            }
        }
        return null
    }

    private fun getToolContainer(activity: Activity): ViewGroup? {
        toolContainerRef?.get()?.let { return it }
        val container = findViewByName(activity, "ll_shape_tool_container") as? ViewGroup
        if (container != null) {
            toolContainerRef = WeakReference(container)
        }
        return container
    }

    /**
     * 工具选择核心逻辑：
     * 查找子 View 中的 Enum 字段（用于兼容 StarNote 的代码混淆）
     */
    private fun findEnumField(clazz: Class<*>, sampleInstance: Any): Field? {
        return classEnumFieldCache.computeIfAbsent(clazz) {
            var current: Class<*>? = it
            while (current != null && current.name != "android.view.View" && current != Any::class.java) {
                for (field in current.declaredFields) {
                    try {
                        field.isAccessible = true
                        val value = field.get(sampleInstance)
                        if (value != null && (value is Enum<*> || value.javaClass.isEnum)) {
                            return@computeIfAbsent field
                        }
                    } catch (_: Throwable) {}
                }
                current = current.superclass
            }
            null
        }
    }

    private fun getToolEnumName(view: View): String? {
        val field = findEnumField(view.javaClass, view) ?: return null
        return try {
            val value = field.get(view)
            if (value != null && (value is Enum<*> || value.javaClass.isEnum)) {
                value.toString().uppercase()
            } else null
        } catch (_: Throwable) {
            null
        }
    }

    private fun findSelectedField(clazz: Class<*>, sampleInstance: Any): Field? {
        return classSelectedFieldCache.computeIfAbsent(clazz) {
            var current: Class<*>? = it
            var candidateField: Field? = null
            while (current != null && current.name != "android.view.View" && current != Any::class.java) {
                for (field in current.declaredFields) {
                    if (field.type == Boolean::class.javaPrimitiveType) {
                        try {
                            field.isAccessible = true
                            if (field.name == "t" || field.name == "isSelect") {
                                return@computeIfAbsent field
                            }
                            if (candidateField == null) {
                                candidateField = field
                            }
                        } catch (_: Throwable) {}
                    }
                }
                current = current.superclass
            }
            candidateField
        }
    }

    private fun isToolSelected(child: View): Boolean {
        if (child.isSelected) return true
        val field = findSelectedField(child.javaClass, child) ?: return false
        return try {
            field.getBoolean(child)
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 工具选择核心逻辑：
     * 1. 快路径：直接从 WeakReference 缓存中获取 View 点击
     * 2. 慢路径：遍历容器子 View，反射提取 Enum 类型并填充缓存
     *
     * 保护逻辑：若目标工具已处于选中态，禁止重复 performClick，避免误触发 StarNote 双击弹出次级配置弹窗。
     */
    private fun selectToolByEnumName(activity: Activity, vararg targetEnumNames: String): Boolean {
        // 1. 快路径
        for (target in targetEnumNames) {
            val upperTarget = target.uppercase()
            val cachedView = toolViewCache[upperTarget]?.get()
            if (cachedView != null && cachedView.isAttachedToWindow) {
                if (isToolSelected(cachedView)) {
                    Log.d(TAG, "Fast-path: tool $upperTarget is already selected")
                    return true
                }
                cachedView.performClick()
                Log.d(TAG, "Fast-path: selected tool $upperTarget")
                return true
            }
        }

        // 2. 慢路径
        val container = getToolContainer(activity) ?: return false
        var targetFoundView: View? = null
        var foundEnumName: String? = null

        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            val enumName = getToolEnumName(child)
            if (enumName != null) {
                toolViewCache[enumName] = WeakReference(child)
                if (targetFoundView == null && targetEnumNames.any { it.equals(enumName, ignoreCase = true) }) {
                    targetFoundView = child
                    foundEnumName = enumName
                }
            }
        }

        if (targetFoundView != null) {
            if (isToolSelected(targetFoundView)) {
                Log.d(TAG, "Cold-path: tool $foundEnumName is already selected")
                return true
            }
            targetFoundView.performClick()
            Log.d(TAG, "Cold-path: selected tool $foundEnumName and cached")
            return true
        }

        return false
    }

    fun executeAction(activity: Activity, action: ActionType, isDown: Boolean) {
        when (action) {
            ActionType.TOGGLE_ERASER_HOLD -> {
                if (isDown) onEraserHoldDown(activity) else onEraserHoldUp(activity)
            }
            ActionType.SELECT_PEN -> if (isDown) selectPen(activity)
            ActionType.SELECT_ERASER -> if (isDown) selectEraser(activity)
            ActionType.SELECT_LASSO -> if (isDown) selectLasso(activity)
        }
    }

    fun selectEraser(activity: Activity) {
        val success = selectToolByEnumName(activity, "ERASER")
        if (!success) {
            val container = getToolContainer(activity) ?: return
            if (container.childCount > 3) {
                val child = container.getChildAt(3)
                if (!isToolSelected(child)) {
                    child.performClick()
                }
            }
        }
    }

    fun selectPen(activity: Activity) {
        val success = selectToolByEnumName(activity, "INK_PEN", "MARKER_PEN", "BALL_PEN", "PENCIL", "BRUSH_PEN", "PEN")
        if (!success) {
            val container = getToolContainer(activity) ?: return
            if (container.childCount > 0) {
                val child = container.getChildAt(0)
                if (!isToolSelected(child)) {
                    child.performClick()
                }
            }
        }
    }

    fun selectLasso(activity: Activity) {
        val success = selectToolByEnumName(activity, "LASSO")
        if (!success) {
            val container = getToolContainer(activity) ?: return
            if (container.childCount > 4) {
                val child = container.getChildAt(4)
                if (!isToolSelected(child)) {
                    child.performClick()
                }
            }
        }
    }

    private val PEN_ENUM_NAMES = setOf("INK_PEN", "MARKER_PEN", "BALL_PEN", "PENCIL", "BRUSH_PEN", "PEN")

    /**
     * 按切松回 - 按下阶段：
     * 1. 记忆当前画笔工具（若当前为套索等非画笔工具，绝不记忆，松开时直接回默认画笔）；
     * 2. 切换至橡皮擦；
     * 3. 标记 isEraserHolding = true，屏蔽后续多余的按键重发；
     * 4. 立即取消任何挂起的延时回退任务（若处于 50ms 防抖窗口内，直接救回并保持橡皮擦）。
     */
    fun onEraserHoldDown(activity: Activity) {
        cancelPendingRevert()
        if (isEraserHolding) {
            return
        }
        val container = getToolContainer(activity)
        previousToolViewRef = null
        if (container != null) {
            for (i in 0 until container.childCount) {
                val child = container.getChildAt(i)
                if (isToolSelected(child)) {
                    val enumName = getToolEnumName(child)
                    if (enumName != null && PEN_ENUM_NAMES.contains(enumName)) {
                        previousToolViewRef = WeakReference(child)
                        Log.d(TAG, "onEraserHoldDown: memorized previous pen tool $enumName")
                    }
                    break
                }
            }
        }
        isEraserHolding = true
        Log.d(TAG, "onEraserHoldDown: switching to eraser")
        selectEraser(activity)
    }

    /**
     * 按切松回 - 松开阶段：
     * 无论笔尖是否在屏幕表面触控，只要收到按键松开指令，通过 50ms 微轻量防抖定时器缓冲：
     * 1. 若 50ms 内驱动因落笔产生瞬态 DOWN 脉冲，立即在 onEraserHoldDown 中取消回退，平滑无缝保持橡皮擦；
     * 2. 若 50ms 内无新按键，判定为真实松开按键，无论此时笔尖是否在屏幕触控，立即切回画笔，达到极致跟手的“松手即回”体验。
     */
    fun onEraserHoldUp(activity: Activity) {
        if (!isEraserHolding) {
            return
        }
        schedulePendingRevert(activity, delayMs = 50L)
    }

    /**
     * 触控与悬停状态监听，处理快捷键硬件通过 MotionEvent 携带按键状态上报的情况
     */
    fun onMotionEvent(activity: Activity, event: MotionEvent) {
        checkStylusButtonState(activity, event)
    }

    private fun checkStylusButtonState(activity: Activity, event: MotionEvent) {
        val buttonState = event.buttonState
        val isPrimaryStylusPressed = (buttonState and (MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_SECONDARY or 32 or 2)) != 0 ||
                (event.pointerCount > 0 && event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER)
        if (isPrimaryStylusPressed) {
            if (!isEraserHolding) {
                onEraserHoldDown(activity)
            } else {
                cancelPendingRevert()
            }
        }
    }

    private fun schedulePendingRevert(activity: Activity, delayMs: Long = 50L) {
        cancelPendingRevert()
        val runnable = Runnable {
            if (isEraserHolding) {
                Log.d(TAG, "schedulePendingRevert: executing revert to pen")
                isEraserHolding = false
                val prev = previousToolViewRef?.get()
                previousToolViewRef = null
                if (prev != null && prev.isAttachedToWindow) {
                    if (!isToolSelected(prev)) {
                        prev.performClick()
                        Log.d(TAG, "Reverted to previous pen tool: ${getToolEnumName(prev)}")
                    }
                } else {
                    selectPen(activity)
                    Log.d(TAG, "Reverted to default pen tool")
                }
            }
        }
        pendingRevertRunnable = runnable
        mainHandler.postDelayed(runnable, delayMs)
    }

    private fun cancelPendingRevert() {
        pendingRevertRunnable?.let {
            mainHandler.removeCallbacks(it)
            pendingRevertRunnable = null
        }
    }
}
