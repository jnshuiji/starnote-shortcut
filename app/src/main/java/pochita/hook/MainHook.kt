package pochita.hook

import android.app.Activity
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import android.os.Build
import pochita.model.ActionType
import java.util.concurrent.atomic.AtomicBoolean

/**
 * StarNote (com.onyx.galaxy.note) Xposed 核心注入模块
 *
 * 1. 提供 x86_64 虚拟化环境动态链接库兼容补丁（ARM64 原生真机自动跳过）；
 * 2. 在 StarNote 设置页精确挂载「快捷键设置」一级菜单与行内零弹窗配置面板；
 * 3. 在画板 NoteScribbleActivity 中秒级拦截并调度各快捷键工具动作。
 */
class MainHook : XposedModule() {

    companion object {
        const val TAG = "StarNoteHook"
        private const val TARGET_PACKAGE = "com.onyx.galaxy.note"
        private const val TARGET_CANVAS_ACTIVITY = "com.onyx.galaxy.note.editor.ui.NoteScribbleActivity"

        private val isShortcutHooked = AtomicBoolean(false)
        private var pendingUpConsumeKeyCode: Int = 0
    }

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        super.onModuleLoaded(param)
        Log.i(TAG, "StarNote module loaded in process: ${param.processName}")
    }

    override fun onPackageReady(param: PackageReadyParam) {
        super.onPackageReady(param)
        if (param.packageName != TARGET_PACKAGE) return

        Log.i(TAG, "Target package $TARGET_PACKAGE ready, installing hooks...")
        pochita.hook.patch.ArchitecturePatcher.apply(this, param.classLoader)
        applyShortcutHooks(param.classLoader)
    }

    private val isCanvasTargetedHooked = AtomicBoolean(false)

    /**
     * 安装快捷键菜单注入、按键录制拦截以及画板工具调度挂钩
     */
    private fun applyShortcutHooks(classLoader: ClassLoader): Boolean {
        if (isShortcutHooked.get()) return true

        return try {
            // 安装设置页左侧菜单及面板注入
            ShortcutSettingsInjector.install(this, classLoader)

            // 安装 Activity 生命周期与按键分发挂钩
            installActivityHooks()

            try {
                val canvasClass = classLoader.loadClass(TARGET_CANVAS_ACTIVITY)
                installCanvasTargetedHooks(canvasClass)
            } catch (_: Throwable) {}

            try {
                installRenderManagerHook(classLoader)
            } catch (_: Throwable) {}

            isShortcutHooked.set(true)
            Log.i(TAG, "Successfully installed shortcut mapping hooks")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to install shortcut mapping hooks", t)
            false
        }
    }

    private fun installRenderManagerHook(classLoader: ClassLoader) {
        try {
            val renderManagerClass = classLoader.loadClass("com.onyx.android.sdk.universal.editor.display.RenderManager")
            for (method in renderManagerClass.declaredMethods) {
                if (method.name == "updateEditorView" && method.parameterTypes.size == 2) {
                    hook(method).intercept { chain ->
                        val result = chain.proceed()
                        val view = chain.args[0] as? View
                        val eventBus = chain.args[1]
                        if (view != null && eventBus != null) {
                            NoteScribbleHook.notifyEditorViewAndEventBus(view, eventBus)
                        }
                        result
                    }
                    Log.i(TAG, "Hooked RenderManager.updateEditorView for direct EventBus injection")
                    break
                }
            }
        } catch (t: Throwable) {
            Log.d(TAG, "RenderManager hook notice: ${t.message}")
        }
    }

    /**
     * 在 NoteScribbleActivity 具体子类上直接安装精准 Hook：
     * 1. dispatchKeyEvent：0ms 顶层截获画板快捷键，阻断 StarNote 内部 stylusPenHelper 冲突；
     * 2. dispatchTouchEvent：100% 捕获屏幕触控与提笔，驱动快捷键防抖回退与画布拖动消费；
     * 3. onGenericMotionEvent：捕获悬停与手势移动事件，驱动悬空拖动画布。
     */
    private fun installCanvasTargetedHooks(canvasClass: Class<*>) {
        if (isCanvasTargetedHooked.getAndSet(true)) return

        Log.i(TAG, "Installing targeted hooks on ${canvasClass.name}...")

        // 1. NoteScribbleActivity.dispatchKeyEvent
        try {
            val keyMethod = canvasClass.getDeclaredMethod("dispatchKeyEvent", KeyEvent::class.java).apply {
                isAccessible = true
            }
            hook(keyMethod).intercept { chain ->
                val activity = chain.thisObject as? Activity
                val event = chain.args[0] as? KeyEvent
                if (activity != null && event != null) {
                    val consumed = handleCanvasKeyEvent(activity, event)
                    if (consumed) return@intercept true
                }
                chain.proceed()
            }
            Log.i(TAG, "Successfully hooked ${canvasClass.simpleName}.dispatchKeyEvent")
        } catch (t: Throwable) {
            Log.w(TAG, "Could not hook ${canvasClass.simpleName}.dispatchKeyEvent: ${t.message}")
        }

        // 2. NoteScribbleActivity.dispatchTouchEvent
        try {
            val touchMethod = canvasClass.getDeclaredMethod("dispatchTouchEvent", MotionEvent::class.java).apply {
                isAccessible = true
            }
            hook(touchMethod).intercept { chain ->
                val activity = chain.thisObject as? Activity
                val event = chain.args[0] as? MotionEvent
                if (activity != null && event != null) {
                    val consumed = NoteScribbleHook.onTouchEvent(activity, event)
                    if (consumed) return@intercept true
                }
                chain.proceed()
            }
            Log.i(TAG, "Successfully hooked ${canvasClass.simpleName}.dispatchTouchEvent")
        } catch (t: Throwable) {
            Log.w(TAG, "Could not hook ${canvasClass.simpleName}.dispatchTouchEvent: ${t.message}")
        }

        // 3. NoteScribbleActivity.onGenericMotionEvent
        try {
            val genericMethod = canvasClass.getDeclaredMethod("onGenericMotionEvent", MotionEvent::class.java).apply {
                isAccessible = true
            }
            hook(genericMethod).intercept { chain ->
                val activity = chain.thisObject as? Activity
                val event = chain.args[0] as? MotionEvent
                if (activity != null && event != null) {
                    NoteScribbleHook.onGenericMotionEvent(activity, event)
                }
                chain.proceed()
            }
            Log.i(TAG, "Successfully hooked ${canvasClass.simpleName}.onGenericMotionEvent")
        } catch (t: Throwable) {
            Log.w(TAG, "Could not hook ${canvasClass.simpleName}.onGenericMotionEvent: ${t.message}")
        }
    }

    private fun handleCanvasKeyEvent(activity: Activity, event: KeyEvent): Boolean {
        if (isEditingText(activity)) {
            return false
        }

        val keyCode = event.keyCode
        if (keyCode <= 0) return false

        val action = when (keyCode) {
            ShortcutSettingsState.keyDragCanvas -> ActionType.DRAG_CANVAS_HOLD
            ShortcutSettingsState.keyToggleEraser -> ActionType.TOGGLE_ERASER_HOLD
            ShortcutSettingsState.keyLasso -> ActionType.SELECT_LASSO
            ShortcutSettingsState.keyPen -> ActionType.SELECT_PEN
            ShortcutSettingsState.keyEraser -> ActionType.SELECT_ERASER
            else -> return false
        }

        val isHoldAction = action == ActionType.DRAG_CANVAS_HOLD || action == ActionType.TOGGLE_ERASER_HOLD

        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.repeatCount == 0) {
                activity.runOnUiThread {
                    NoteScribbleHook.executeAction(activity, action, isDown = true)
                }
            } else {
                activity.runOnUiThread {
                    NoteScribbleHook.onKeyRepeat(activity, action)
                }
            }
            return true
        } else if (event.action == KeyEvent.ACTION_UP) {
            if (isHoldAction) {
                activity.runOnUiThread {
                    NoteScribbleHook.executeAction(activity, action, isDown = false)
                }
            }
            return true
        }

        return false
    }

    private fun installActivityHooks() {
        val activityClass = Activity::class.java

        // 0. Activity.onCreate: 实时捕获已解包加载真实 Dex 的 Application/Activity ClassLoader
        try {
            val onCreateMethod = activityClass.getDeclaredMethod("onCreate", android.os.Bundle::class.java).apply {
                isAccessible = true
            }
            hook(onCreateMethod).intercept { chain ->
                val activity = chain.thisObject as? Activity
                if (activity != null) {
                    val className = activity.javaClass.name
                    if (className.contains("SettingsActivity")) {
                        ShortcutSettingsInjector.attachActivity(activity)
                        ShortcutSettingsInjector.install(this@MainHook, activity.classLoader)
                    } else if (className == TARGET_CANVAS_ACTIVITY) {
                        ShortcutSettingsInjector.install(this@MainHook, activity.classLoader)
                        installCanvasTargetedHooks(activity.javaClass)
                        installRenderManagerHook(activity.classLoader)
                    }
                }
                chain.proceed()
            }
        } catch (_: Throwable) {}

        // 1. Activity.onResume
        val onResumeMethod = activityClass.getDeclaredMethod("onResume").apply { isAccessible = true }
        hook(onResumeMethod).intercept { chain ->
            val result = chain.proceed()
            val activity = chain.thisObject as? Activity
            if (activity != null) {
                val className = activity.javaClass.name
                if (className == TARGET_CANVAS_ACTIVITY) {
                    installCanvasTargetedHooks(activity.javaClass)
                    ShortcutSettingsState.initIfNeeded(activity)
                    NoteScribbleHook.onActivityResumed(activity)
                } else if (className.contains("SettingsActivity")) {
                    ShortcutSettingsInjector.attachActivity(activity)
                    ShortcutSettingsInjector.install(this@MainHook, activity.classLoader)
                    ShortcutSettingsState.initIfNeeded(activity)
                }
            }
            result
        }

        // 2. Activity.onPause
        val onPauseMethod = activityClass.getDeclaredMethod("onPause").apply { isAccessible = true }
        hook(onPauseMethod).intercept { chain ->
            val activity = chain.thisObject as? Activity
            if (activity != null) {
                val className = activity.javaClass.name
                if (className == TARGET_CANVAS_ACTIVITY) {
                    NoteScribbleHook.onActivityPaused(activity)
                } else if (className.contains("SettingsActivity")) {
                    ShortcutSettingsState.stopRecording()
                }
            }
            chain.proceed()
        }

        // 3. Activity.dispatchKeyEvent (兜底)
        val dispatchKeyEventMethod = activityClass.getDeclaredMethod("dispatchKeyEvent", KeyEvent::class.java).apply {
            isAccessible = true
        }
        hook(dispatchKeyEventMethod).intercept { chain ->
            val activity = chain.thisObject as? Activity
            val event = chain.args[0] as? KeyEvent

            if (activity != null && event != null) {
                val className = activity.javaClass.name

                // 设置页中的行内按键录制捕获
                if (className.contains("SettingsActivity")) {
                    val recAction = ShortcutSettingsState.recordingAction
                    if (recAction != null) {
                        if (event.action == KeyEvent.ACTION_DOWN) {
                            val keyCode = event.keyCode
                            pendingUpConsumeKeyCode = keyCode
                            when (keyCode) {
                                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                                    ShortcutSettingsState.stopRecording()
                                }
                                KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL -> {
                                    ShortcutSettingsState.setKeyCode(activity, recAction, 0)
                                    ShortcutSettingsState.stopRecording()
                                }
                                else -> {
                                    ShortcutSettingsState.setKeyCode(activity, recAction, keyCode)
                                    ShortcutSettingsState.stopRecording()
                                }
                            }
                        }
                        return@intercept true
                    } else if (event.action == KeyEvent.ACTION_UP && pendingUpConsumeKeyCode != 0 && event.keyCode == pendingUpConsumeKeyCode) {
                        pendingUpConsumeKeyCode = 0
                        return@intercept true
                    }
                }

                // 画板界面中的快捷键分发 (兜底)
                if (className == TARGET_CANVAS_ACTIVITY) {
                    val consumed = handleCanvasKeyEvent(activity, event)
                    if (consumed) return@intercept true
                }
            }

            chain.proceed()
        }

        // 4. Activity.dispatchTouchEvent (画板笔触与提笔状态追踪及画布拖动消费)
        try {
            val dispatchTouchEventMethod = activityClass.getDeclaredMethod("dispatchTouchEvent", MotionEvent::class.java).apply {
                isAccessible = true
            }
            hook(dispatchTouchEventMethod).intercept { chain ->
                val activity = chain.thisObject as? Activity
                val event = chain.args[0] as? MotionEvent
                if (activity != null && event != null && activity.javaClass.name == TARGET_CANVAS_ACTIVITY) {
                    val consumed = NoteScribbleHook.onTouchEvent(activity, event)
                    if (consumed) return@intercept true
                }
                chain.proceed()
            }
        } catch (_: Throwable) {}

        // 5. Activity.dispatchGenericMotionEvent (画板手写笔悬停与按键状态追踪)
        try {
            val dispatchGenericMotionEventMethod = activityClass.getDeclaredMethod("dispatchGenericMotionEvent", MotionEvent::class.java).apply {
                isAccessible = true
            }
            hook(dispatchGenericMotionEventMethod).intercept { chain ->
                val activity = chain.thisObject as? Activity
                val event = chain.args[0] as? MotionEvent
                if (activity != null && event != null && activity.javaClass.name == TARGET_CANVAS_ACTIVITY) {
                    NoteScribbleHook.onGenericMotionEvent(activity, event)
                }
                chain.proceed()
            }
        } catch (_: Throwable) {}

        // 6. Activity.onBackPressed: 设置页录制状态下拦截物理返回
        try {
            val onBackPressedMethod = activityClass.getDeclaredMethod("onBackPressed").apply { isAccessible = true }
            hook(onBackPressedMethod).intercept { chain ->
                val activity = chain.thisObject as? Activity
                if (activity != null && activity.javaClass.name.contains("SettingsActivity")) {
                    if (ShortcutSettingsState.recordingAction != null) {
                        ShortcutSettingsState.stopRecording()
                        return@intercept null
                    }
                }
                chain.proceed()
            }
        } catch (_: Throwable) {}
    }

    private fun isEditingText(activity: Activity): Boolean {
        val focus = activity.currentFocus ?: return false
        if (focus is EditText) return true
        val className = focus.javaClass.name
        return className.contains("EditText", ignoreCase = true)
    }

    override fun onHotReloading(param: HotReloadingParam): Boolean = true

    override fun onHotReloaded(param: HotReloadedParam) {
        super.onHotReloaded(param)
        Log.i(TAG, "Module hot reloaded")
    }
}
