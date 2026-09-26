package pochita.hook.canvas

import android.app.Activity
import android.util.Log
import android.view.View
import android.view.ViewGroup
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap

/**
 * 画板工具栏子 View 识别与选中控制器
 *
 * 1. 维护工具名称（"PEN", "ERASER", "LASSO" 等）到实际 View 的弱引用快路径缓存；
 * 2. 慢路径反射扫描 View 成员变量中的混淆枚举与选中布尔值；
 * 3. 避免对已选中的工具重复 performClick 触发次级设置弹窗。
 */
object CanvasToolController {
    private const val TAG = "StarNoteToolCtrl"

    val PEN_ENUM_NAMES = setOf("INK_PEN", "MARKER_PEN", "BALL_PEN", "PENCIL", "BRUSH_PEN", "PEN")

    private val toolViewCache = ConcurrentHashMap<String, WeakReference<View>>()
    private var toolContainerRef: WeakReference<ViewGroup>? = null

    // 静态反射字段缓存，避免每次按键重复反射 declaredFields
    private val classEnumFieldCache = ConcurrentHashMap<Class<*>, Field?>()
    private val classSelectedFieldCache = ConcurrentHashMap<Class<*>, Field?>()

    fun clearCaches() {
        toolViewCache.clear()
        toolContainerRef = null
        classEnumFieldCache.clear()
        classSelectedFieldCache.clear()
    }

    fun findViewByName(activity: Activity, name: String): View? {
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

    fun getToolEnumName(view: View): String? {
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

    fun isToolSelected(child: View): Boolean {
        if (child.isSelected) return true
        val field = findSelectedField(child.javaClass, child) ?: return false
        return try {
            field.getBoolean(child)
        } catch (_: Throwable) {
            false
        }
    }

    fun selectToolByEnumName(activity: Activity, vararg targetEnumNames: String): Boolean {
        // 1. 快路径：从弱引用缓存中获取直接点击
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

        // 2. 慢路径：遍历工具栏容器
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

    fun getCurrentSelectedPenTool(activity: Activity): View? {
        val container = getToolContainer(activity) ?: return null
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            if (isToolSelected(child)) {
                val enumName = getToolEnumName(child)
                if (enumName != null && PEN_ENUM_NAMES.contains(enumName)) {
                    return child
                }
                break
            }
        }
        return null
    }
}
