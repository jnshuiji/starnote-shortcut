package pochita.hook

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.content.ContextWrapper
import androidx.core.content.ContextCompat
import pochita.model.ActionType
import pochita.model.KeyConfig
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

/**
 * StarNote 设置页快捷键注入引擎
 *
 * 负责在 StarNote 设置页左侧菜单栏中精确插入「快捷键设置」条目（置于手写笔设置正上方），
 * 并在右侧容器中渲染完全对齐原生主题的行内零弹窗快捷键录制控制面板。
 */
object ShortcutSettingsInjector {
    private const val TAG = "ShortcutInjector"
    private const val TAG_PANEL = "starnote_shortcut_settings_panel"
    private val isHooked = AtomicBoolean(false)

    private class PillViewHolder(
        val pill: LinearLayout,
        val hintText: TextView,
        val keyText: TextView,
        val clearButton: TextView
    )

    private var activeActivityRef: WeakReference<Activity>? = null
    private val pillViewHolderMap = mutableMapOf<ActionType, PillViewHolder>()

    fun attachActivity(activity: Activity) {
        activeActivityRef = WeakReference(activity)
    }

    private fun findActivity(context: Context?): Activity? {
        var current = context
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return activeActivityRef?.get()
    }

    /**
     * 安装设置菜单与适配器挂钩
     */
    fun install(mainHook: MainHook, classLoader: ClassLoader): Boolean {
        if (isHooked.get()) return true

        return try {
            val adapterClass = classLoader.loadClass("com.onyx.galaxy.note.settings.ui.adapter.SettingsMenuAdapter")
            val baseAdapterClass = classLoader.loadClass("com.chad.library.adapter4.BaseQuickAdapter")

            // 1. 挂钩 BaseQuickAdapter.submitList: 在 STYLUS 正上方插入快捷键设置菜单占位 (null)
            for (method in baseAdapterClass.declaredMethods) {
                if (method.name == "submitList" && method.parameterTypes.isNotEmpty() &&
                    List::class.java.isAssignableFrom(method.parameterTypes[0])
                ) {
                    mainHook.hook(method).intercept { chain ->
                        val adapter = chain.thisObject
                        if (adapter != null && adapter.javaClass.name.contains("SettingsMenuAdapter")) {
                            val rawList = chain.args[0] as? List<*>
                            if (rawList != null && !rawList.contains(null)) {
                                val stylusIndex = rawList.indexOfFirst { (it as? Enum<*>)?.name == "STYLUS" }
                                if (stylusIndex >= 0) {
                                    val newList = ArrayList(rawList)
                                    newList.add(stylusIndex, null)
                                    val newArgs = chain.args.toTypedArray()
                                    newArgs[0] = newList
                                    return@intercept chain.proceed(newArgs)
                                }
                            }
                        }
                        chain.proceed()
                    }
                }
            }

            // 2. 挂钩 SettingsMenuAdapter.onBindViewHolder: 渲染「快捷键设置」菜单项及选中态
            val onBindMethod = adapterClass.declaredMethods.firstOrNull {
                it.name == "onBindViewHolder" && it.parameterTypes.size == 3
            }
            if (onBindMethod != null) {
                mainHook.hook(onBindMethod).intercept { chain ->
                    val adapter = chain.thisObject
                    val holder = chain.args[0]
                    val position = chain.args[1] as Int
                    val item = chain.args[2]

                    if (item == null) {
                        bindShortcutMenuItem(adapter, holder, position)
                        null
                    } else {
                        val result = chain.proceed()
                        onRegularMenuItemBound(adapter, holder, item)
                        result
                    }
                }
            }

            isHooked.set(true)
            Log.i(TAG, "Successfully installed ShortcutSettingsInjector hooks")
            true
        } catch (t: Throwable) {
            Log.d(TAG, "Postponing ShortcutSettingsInjector hook until classloader loads: ${t.message}")
            false
        }
    }

    /**
     * 绑定左侧「快捷键设置」菜单项视图
     */
    private fun bindShortcutMenuItem(adapter: Any, holder: Any, position: Int) {
        val holderItemView = getHolderItemView(holder) ?: return
        val context = holderItemView.context
        val packageName = context.packageName

        val tvNameId = context.resources.getIdentifier("tv_setting_menu_name", "id", packageName)
        val ivMenuId = context.resources.getIdentifier("iv_menu", "id", packageName)
        val itemContainerId = context.resources.getIdentifier("ll_item_container", "id", packageName)
        val newFeatureId = context.resources.getIdentifier("newFeatureImage", "id", packageName)

        holderItemView.findViewById<TextView>(tvNameId)?.text = "快捷键设置"
        holderItemView.findViewById<View>(newFeatureId)?.visibility = View.GONE

        val kbIconId = context.resources.getIdentifier("ic_keyboard_black_24dp", "drawable", packageName)
        val iconRes = if (kbIconId != 0) kbIconId else context.resources.getIdentifier("selector_settings_tools", "drawable", packageName)
        if (iconRes != 0) {
            holderItemView.findViewById<ImageView>(ivMenuId)?.setImageResource(iconRes)
        }

        val isSelected = isShortcutSelected(adapter)
        holderItemView.isSelected = isSelected
        holderItemView.findViewById<View>(ivMenuId)?.isSelected = isSelected
        val container = holderItemView.findViewById<View>(itemContainerId)
        container?.isSelected = isSelected

        // 点击事件增强：保证直接点击占位条目时将 ViewModel 菜单选中值设为 null
        val clickListener = View.OnClickListener {
            selectShortcutMenu(adapter)
            val act = findActivity(holderItemView.context)
            if (act != null) {
                updatePanelVisibility(act, true)
            }
        }
        holderItemView.setOnClickListener(clickListener)
        container?.setOnClickListener(clickListener)

        val activity = findActivity(holderItemView.context)
        if (activity != null) {
            updatePanelVisibility(activity, isSelected)
        }
    }

    /**
     * 常规菜单项绑定时的右侧面板显隐协同
     */
    private fun onRegularMenuItemBound(adapter: Any, holder: Any, item: Any) {
        try {
            val vm = getSettingsViewModel(adapter) ?: return
            val selectMethod = vm.javaClass.methods.firstOrNull { it.name == "getSelectMenuType" } ?: return
            val liveData = selectMethod.invoke(vm) ?: return
            val getValueMethod = liveData.javaClass.getMethod("getValue")
            val currentVal = getValueMethod.invoke(liveData)

            if (currentVal != null) {
                val holderItemView = getHolderItemView(holder) ?: return
                val activity = findActivity(holderItemView.context)
                if (activity != null) {
                    updatePanelVisibility(activity, false)
                }
            }
        } catch (_: Throwable) {}
    }

    private fun getHolderItemView(holder: Any): View? {
        return try {
            val field = holder.javaClass.getField("itemView")
            field.get(holder) as? View
        } catch (_: Throwable) {
            try {
                val method = holder.javaClass.getMethod("getItemView")
                method.invoke(holder) as? View
            } catch (_: Throwable) {
                null
            }
        }
    }

    private fun isShortcutSelected(adapter: Any): Boolean {
        return try {
            val vm = getSettingsViewModel(adapter) ?: return false
            val selectMethod = vm.javaClass.methods.firstOrNull { it.name == "getSelectMenuType" } ?: return false
            val liveData = selectMethod.invoke(vm) ?: return false
            val getValueMethod = liveData.javaClass.getMethod("getValue")
            val currentVal = getValueMethod.invoke(liveData)
            currentVal == null
        } catch (_: Throwable) {
            false
        }
    }

    private fun selectShortcutMenu(adapter: Any) {
        try {
            val vm = getSettingsViewModel(adapter) ?: return
            val selectMethod = vm.javaClass.methods.firstOrNull { it.name == "getSelectMenuType" } ?: return
            val liveData = selectMethod.invoke(vm) ?: return
            var method: java.lang.reflect.Method? = null
            var cls: Class<*>? = liveData.javaClass
            while (cls != null && cls != Any::class.java) {
                method = cls.declaredMethods.firstOrNull { it.name == "setValue" && it.parameterTypes.size == 1 }
                if (method != null) break
                cls = cls.superclass
            }
            method?.let {
                it.isAccessible = true
                it.invoke(liveData, null)
            }
            try {
                adapter.javaClass.getMethod("notifyDataSetChanged").invoke(adapter)
            } catch (_: Throwable) {}
        } catch (t: Throwable) {
            Log.e(TAG, "Error selecting shortcut menu", t)
        }
    }

    private fun getSettingsViewModel(adapter: Any): Any? {
        val vmField = adapter.javaClass.declaredFields.firstOrNull {
            it.name == "settingViewModel" || it.type.name.contains("SettingsViewModel")
        }?.apply { isAccessible = true }
        return vmField?.get(adapter)
    }

    /**
     * 控制右侧「快捷键设置」面板的显隐与视图挂载
     */
    fun updatePanelVisibility(activity: Activity, show: Boolean) {
        activity.runOnUiThread {
            try {
                val resId = activity.resources.getIdentifier("settings_right", "id", activity.packageName)
                val settingsRight = (if (resId != 0) activity.findViewById<View>(resId) else null) ?: return@runOnUiThread
                val parentLayout = settingsRight.parent as? ViewGroup ?: return@runOnUiThread

                var panel = parentLayout.findViewWithTag<View>(TAG_PANEL)
                if (panel == null) {
                    panel = buildShortcutPanelView(activity).apply {
                        tag = TAG_PANEL
                    }
                    val lp: ViewGroup.LayoutParams = try {
                        val lpClass = parentLayout.context.classLoader.loadClass("androidx.constraintlayout.widget.ConstraintLayout\$LayoutParams")
                        val ctor = lpClass.getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                        val instance = ctor.newInstance(0, 0)
                        lpClass.getField("startToStart").setInt(instance, settingsRight.id)
                        lpClass.getField("endToEnd").setInt(instance, 0) // PARENT_ID is 0
                        lpClass.getField("topToTop").setInt(instance, 0)
                        lpClass.getField("bottomToBottom").setInt(instance, 0)
                        instance as ViewGroup.LayoutParams
                    } catch (t: Throwable) {
                        Log.w(TAG, "Fallback to MarginLayoutParams for panel: ${t.message}")
                        ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    }
                    parentLayout.addView(panel, lp)
                    Log.i(TAG, "Added shortcut settings panel to parentLayout ($parentLayout)")
                }

                if (show) {
                    panel.visibility = View.VISIBLE
                    panel.bringToFront()
                    panel.elevation = 20f
                    panel.translationZ = 20f
                    settingsRight.visibility = View.INVISIBLE
                    refreshPills(activity)
                } else {
                    panel.visibility = View.GONE
                    settingsRight.visibility = View.VISIBLE
                    ShortcutSettingsState.stopRecording()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to update panel visibility", t)
            }
        }
    }

    /**
     * 构建右侧快捷键配置面板视图层次
     */
    private fun buildShortcutPanelView(context: Context): View {
        ShortcutSettingsState.initIfNeeded(context)
        ShortcutSettingsState.onStateChangedListener = {
            (context as? Activity)?.runOnUiThread {
                refreshPills(context)
            }
        }

        val dp = { v: Number -> dpToPx(context, v.toFloat()) }

        val bgRightColor = getThemeColor(context, "theme_background_color_02", Color.parseColor("#F7F8FA"))
        val cardBgColor = Color.WHITE
        val titleColor = getThemeColor(context, "theme_text_color_01", Color.parseColor("#1D2939"))
        val descColor = getThemeColor(context, "theme_text_color_02", Color.parseColor("#667085"))
        val dividerColor = getThemeColor(context, "theme_divider_color_01", Color.parseColor("#EAECF0"))

        val root = FrameLayout(context).apply {
            setBackgroundColor(bgRightColor)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                ShortcutSettingsState.stopRecording()
            }
        }

        val scrollView = ScrollView(context).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }

        val contentLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(28), dp(28), dp(36))
        }

        // 顶栏大标题
        val titleView = TextView(context).apply {
            text = "快捷键设置"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(titleColor)
        }
        contentLayout.addView(titleView)

        // 副标题描述
        val subtitleView = TextView(context).apply {
            text = "点击右侧卡片录制快捷键，按下对应按键即可完成映射"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(descColor)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(6)
            }
            layoutParams = lp
        }
        contentLayout.addView(subtitleView)

        // 细分割线
        val divider = View(context).apply {
            setBackgroundColor(dividerColor)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(0.8f)
            ).apply {
                topMargin = dp(18)
                bottomMargin = dp(20)
            }
            layoutParams = lp
        }
        contentLayout.addView(divider)

        // 四项操作卡片列表
        pillViewHolderMap.clear()
        for (action in ActionType.entries) {
            val card = buildActionRow(context, action, titleColor, descColor, cardBgColor, dp)
            contentLayout.addView(card)
        }

        scrollView.addView(contentLayout)
        root.addView(scrollView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        return root
    }

    private fun buildActionRow(
        context: Context,
        action: ActionType,
        titleColor: Int,
        descColor: Int,
        cardBgColor: Int,
        dp: (Number) -> Int
    ): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val rippleRes = context.resources.getIdentifier("theme_ripple_btn_white_bg_radius_12", "drawable", context.packageName)
            if (rippleRes != 0) {
                setBackgroundResource(rippleRes)
            } else {
                val cardDrawable = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(12).toFloat()
                    setColor(cardBgColor)
                    setStroke(dp(1), Color.parseColor("#EDF0F3"))
                }
                background = cardDrawable
            }
            setPadding(dp(18), dp(16), dp(18), dp(16))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(12)
            }
            layoutParams = lp
        }

        val textContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            layoutParams = lp
        }

        val actionTitle = TextView(context).apply {
            text = action.title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(titleColor)
        }
        textContainer.addView(actionTitle)

        val actionDesc = TextView(context).apply {
            text = action.description
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(descColor)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(3)
            }
            layoutParams = lp
        }
        textContainer.addView(actionDesc)

        card.addView(textContainer)

        val pillView = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isBaselineAligned = false
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(36)
            ).apply {
                marginStart = dp(16)
                gravity = Gravity.CENTER_VERTICAL
            }
            layoutParams = lp
        }

        val hintText = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            gravity = Gravity.CENTER
            includeFontPadding = false
            isClickable = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
        }
        pillView.addView(hintText)

        val keyText = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            includeFontPadding = false
            isClickable = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
        }
        pillView.addView(keyText)

        val clearButton = TextView(context).apply {
            text = "✕"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(dp(26), dp(26)).apply {
                gravity = Gravity.CENTER_VERTICAL
                marginStart = dp(6)
            }
        }
        pillView.addView(clearButton)

        val holder = PillViewHolder(pillView, hintText, keyText, clearButton)
        pillViewHolderMap[action] = holder

        updatePill(context, holder, action, dp)

        card.addView(pillView)
        return card
    }

    private fun updatePill(
        context: Context,
        holder: PillViewHolder,
        action: ActionType,
        dp: (Number) -> Int
    ) {
        val isRecording = (ShortcutSettingsState.recordingAction == action)
        val keyCode = ShortcutSettingsState.getKeyCode(action)
        val accentOrange = Color.parseColor("#FF7A00")
        val defaultPillBg = Color.parseColor("#F2F4F7")
        val pillBorderColor = Color.parseColor("#E4E7EC")
        val textColorPrimary = Color.parseColor("#1D2939")
        val textColorDesc = Color.parseColor("#667085")
        val radius = dp(18).toFloat()

        if (isRecording) {
            // 录制激活态：高亮橙色细边框与提示文案
            holder.pill.minimumWidth = dp(130)
            holder.pill.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = radius
                setColor(Color.parseColor("#FFF7ED"))
                setStroke(dp(1.5f), accentOrange)
            }
            holder.pill.setPadding(dp(16), 0, dp(16), 0)
            holder.pill.setOnClickListener {
                ShortcutSettingsState.stopRecording()
            }

            holder.hintText.visibility = View.VISIBLE
            holder.hintText.text = "请按下快捷键..."
            holder.hintText.setTextColor(Color.parseColor("#EA580C"))
            holder.hintText.typeface = Typeface.DEFAULT_BOLD

            holder.keyText.visibility = View.GONE
            holder.clearButton.visibility = View.GONE
            holder.clearButton.setOnClickListener(null)

        } else if (keyCode > 0) {
            // 已配置状态：[ Key  ✕ ]
            val keyName = KeyConfig.getKeyDisplayName(keyCode)
            holder.pill.minimumWidth = dp(84)
            holder.pill.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = radius
                setColor(defaultPillBg)
                setStroke(dp(1), pillBorderColor)
            }
            holder.pill.setPadding(dp(16), 0, dp(8), 0)
            holder.pill.setOnClickListener {
                ShortcutSettingsState.startRecording(action)
            }

            holder.hintText.visibility = View.GONE

            holder.keyText.visibility = View.VISIBLE
            holder.keyText.text = keyName
            holder.keyText.setTextColor(textColorPrimary)

            holder.clearButton.visibility = View.VISIBLE
            holder.clearButton.setTextColor(Color.parseColor("#98A2B3"))
            holder.clearButton.setOnClickListener {
                ShortcutSettingsState.setKeyCode(context, action, 0)
            }

        } else {
            // 未配置状态：[ ⌨ 点击录制快捷键 ]
            holder.pill.minimumWidth = dp(130)
            holder.pill.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = radius
                setColor(defaultPillBg)
                setStroke(dp(1), pillBorderColor)
            }
            holder.pill.setPadding(dp(16), 0, dp(16), 0)
            holder.pill.setOnClickListener {
                ShortcutSettingsState.startRecording(action)
            }

            holder.hintText.visibility = View.VISIBLE
            holder.hintText.text = "⌨ 点击录制快捷键"
            holder.hintText.setTextColor(textColorDesc)
            holder.hintText.typeface = Typeface.DEFAULT

            holder.keyText.visibility = View.GONE
            holder.clearButton.visibility = View.GONE
            holder.clearButton.setOnClickListener(null)
        }
    }

    private fun refreshPills(context: Context) {
        val dp = { v: Number -> dpToPx(context, v.toFloat()) }
        for ((action, holder) in pillViewHolderMap) {
            updatePill(context, holder, action, dp)
        }
    }

    private fun dpToPx(context: Context, dp: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            context.resources.displayMetrics
        ).toInt()
    }

    private fun getThemeColor(context: Context, name: String, fallback: Int): Int {
        val id = context.resources.getIdentifier(name, "color", context.packageName)
        if (id != 0) {
            try {
                return ContextCompat.getColor(context, id)
            } catch (_: Throwable) {}
        }
        return fallback
    }
}
