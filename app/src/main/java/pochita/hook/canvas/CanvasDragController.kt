package pochita.hook.canvas

import android.app.Activity
import android.util.Log
import android.view.MotionEvent
import android.view.View
import java.lang.ref.WeakReference
import java.lang.reflect.Constructor
import java.lang.reflect.Method

/**
 * 无限画布手写笔接触拖动与 EventBus 滚动引擎
 *
 * 1. 拦截并消费拖动过程中的 MotionEvent，完全阻断 StarNote 书写引擎，确保不产生任何墨迹；
 * 2. 具备 isConsumingUntilTouchUp 提笔保护机制，防止中途松开按键时漏画残笔；
 * 3. 动态发现与缓存 EditorView 与 EventBus，以纳秒级反射直接派发 ScrollBegin/Scrolling/ScrollEnd 事件。
 */
object CanvasDragController {
    private const val TAG = "StarNoteDragCtrl"

    @Volatile
    var isDragCanvasHolding = false
        private set

    private var isTouchDragging = false
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var isConsumingUntilTouchUp = false

    private var editorViewRef: WeakReference<View>? = null
    private var eventBusRef: WeakReference<Any>? = null
    private var eventBusPostMethod: Method? = null
    private var scrollBeginConstructor: Constructor<*>? = null
    private var scrollingConstructor: Constructor<*>? = null
    private var scrollEndConstructor: Constructor<*>? = null

    fun clearCaches() {
        isDragCanvasHolding = false
        isTouchDragging = false
        isConsumingUntilTouchUp = false
        editorViewRef = null
        eventBusRef = null
    }

    fun onDragCanvasDown(activity: Activity) {
        if (isDragCanvasHolding) return
        isDragCanvasHolding = true
        Log.d(TAG, "onDragCanvasDown: drag canvas mode enabled")
    }

    fun onDragCanvasUp(activity: Activity) {
        if (!isDragCanvasHolding) return
        isDragCanvasHolding = false
        Log.d(TAG, "onDragCanvasUp: drag canvas mode disabled")
        if (isTouchDragging) {
            isTouchDragging = false
            postScrollEnd(activity)
            isConsumingUntilTouchUp = true
        }
    }

    /**
     * 屏幕触控事件拦截与画布拖动分发
     * 当拖动画布激活或处于提笔消费保护期时，返回 true 拦截并消费事件，阻止墨水绘制
     */
    fun onTouchEvent(activity: Activity, event: MotionEvent): Boolean {
        if (!isDragCanvasHolding && !isConsumingUntilTouchUp) {
            return false
        }

        val action = event.actionMasked
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                if (isDragCanvasHolding) {
                    isTouchDragging = true
                    isConsumingUntilTouchUp = false
                    touchStartX = event.x
                    touchStartY = event.y
                    postScrollBegin(activity, 0f, 0f)
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (isTouchDragging) {
                    val offsetX = -(event.x - touchStartX)
                    val offsetY = -(event.y - touchStartY)
                    postScrolling(activity, offsetX, offsetY, 0)
                    return true
                } else if (isConsumingUntilTouchUp) {
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isTouchDragging) {
                    isTouchDragging = false
                    postScrollEnd(activity)
                    isConsumingUntilTouchUp = false
                    return true
                } else if (isConsumingUntilTouchUp) {
                    isConsumingUntilTouchUp = false
                    return true
                }
            }
        }
        return false
    }

    fun notifyEditorViewAndEventBus(view: View, eventBus: Any) {
        editorViewRef = WeakReference(view)
        eventBusRef = WeakReference(eventBus)
        initEventBusReflection(eventBus, view.javaClass.classLoader ?: view.context.classLoader)
        Log.i(TAG, "Captured EditorView and EventBus directly from RenderManager")
    }

    private fun initEventBusReflection(bus: Any, classLoader: ClassLoader?) {
        if (classLoader == null) return
        if (eventBusPostMethod != null && scrollBeginConstructor != null && scrollingConstructor != null) {
            return
        }
        try {
            eventBusPostMethod = bus.javaClass.getMethod("post", Any::class.java)

            val beginCls = classLoader.loadClass("com.onyx.android.sdk.universal.editor.events.app.ScrollBeginEvent")
            scrollBeginConstructor = beginCls.getDeclaredConstructor(Float::class.javaPrimitiveType, Float::class.javaPrimitiveType).apply {
                isAccessible = true
            }

            val scrollCls = classLoader.loadClass("com.onyx.android.sdk.universal.editor.events.app.ScrollingEvent")
            scrollingConstructor = try {
                scrollCls.getDeclaredConstructor(Float::class.javaPrimitiveType, Float::class.javaPrimitiveType, Int::class.javaPrimitiveType).apply {
                    isAccessible = true
                }
            } catch (_: Throwable) {
                scrollCls.constructors.firstOrNull()?.apply { isAccessible = true }
            }

            val endCls = classLoader.loadClass("com.onyx.android.sdk.universal.editor.events.app.ScrollEndEvent")
            scrollEndConstructor = endCls.getDeclaredConstructor().apply {
                isAccessible = true
            }
            Log.i(TAG, "Successfully initialized EventBus scroll event constructors")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to init EventBus reflection", t)
        }
    }

    private fun getOrFindEventBus(activity: Activity): Any? {
        eventBusRef?.get()?.let { return it }

        val editorView = CanvasToolController.findViewByName(activity, "editor_view") ?: return null
        editorViewRef = WeakReference(editorView)

        try {
            val getEventBusMethod = editorView.javaClass.getMethod("getEventBus")
            val bus = getEventBusMethod.invoke(editorView)
            if (bus != null) {
                initEventBusReflection(bus, editorView.javaClass.classLoader ?: activity.classLoader)
                eventBusRef = WeakReference(bus)
                return bus
            }
        } catch (_: Throwable) {}

        try {
            val busField = editorView.javaClass.getDeclaredField("eventBus").apply { isAccessible = true }
            val bus = busField.get(editorView)
            if (bus != null) {
                initEventBusReflection(bus, editorView.javaClass.classLoader ?: activity.classLoader)
                eventBusRef = WeakReference(bus)
                return bus
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Could not obtain eventBus from editor_view: ${t.message}")
        }
        return null
    }

    private fun postScrollBegin(activity: Activity, offsetX: Float = 0f, offsetY: Float = 0f) {
        val bus = getOrFindEventBus(activity) ?: return
        try {
            val cons = scrollBeginConstructor ?: return
            val event = cons.newInstance(offsetX, offsetY)
            eventBusPostMethod?.invoke(bus, event)
            Log.d(TAG, "Posted ScrollBeginEvent($offsetX, $offsetY)")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to post ScrollBeginEvent", t)
        }
    }

    private fun postScrolling(activity: Activity, offsetX: Float, offsetY: Float, source: Int = 0) {
        val bus = getOrFindEventBus(activity) ?: return
        try {
            val cons = scrollingConstructor ?: return
            val event = if (cons.parameterTypes.size == 3) {
                cons.newInstance(offsetX, offsetY, source)
            } else {
                cons.newInstance(offsetX, offsetY)
            }
            eventBusPostMethod?.invoke(bus, event)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to post ScrollingEvent", t)
        }
    }

    private fun postScrollEnd(activity: Activity) {
        val bus = getOrFindEventBus(activity) ?: return
        try {
            val cons = scrollEndConstructor ?: return
            val event = cons.newInstance()
            eventBusPostMethod?.invoke(bus, event)
            Log.d(TAG, "Posted ScrollEndEvent")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to post ScrollEndEvent", t)
        }
    }
}
