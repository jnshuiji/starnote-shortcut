# StarNote 快捷键扩展模块

基于 LSPosed (API 102 / LibXposed) 开发的 StarNote (`com.onyx.galaxy.note`) 运行时增强模块。提供原生设置界面就地注入、画板工具调度、按切松回瞬态切换以及输入硬件防抖状态机支持。

---

## 核心特性

- **纯模块架构**：无 Launcher Activity，无后台独立常驻服务，依托宿主生命周期运作，零额外空闲内存占用。
- **原生界面注入**：非侵入式挂钩宿主设置页适配器，就地渲染快捷键配置面板，遵循宿主原生设计规范与调色板，无需弹出对话框。
- **低延迟工具调度**：采用反射缓存与弱引用视图池，避免主线程高频分配；对已选中工具执行幂等抑制，消除二次弹窗干扰。
- **按切松回状态机**：针对笔尖落笔时的驱动瞬态断触信号实现 50ms 防抖滤波；按键释放即刻回退至前序笔刷，无需依赖提笔事件。
- **跨平台运行时兼容**：内置 ABI 动态检测机制，针对 x86_64 虚拟机（如 WSA）自动应用动态链接库兼容补丁，ARM64 物理设备保持原生路径无损运行。

---

## 架构设计

### 1. 入口与兼容层 (`MainHook.kt`)

- **目标包名**：`com.onyx.galaxy.note`。
- **动态 ABI 适配**：读取 `Build.SUPPORTED_ABIS`。检测到 x86/x86_64 环境时，挂钩 Sagittarius 脱壳加载器 `com.sagittarius.v6.b.t` 的符号解析方法，修正 APEX 动态库路径误判；ARM 架构下跳过该补丁以规避 `UnsatisfiedLinkError`。
- **子类虚表挂钩**：针对画板核心界面 `NoteScribbleActivity` 显式重写的 `dispatchKeyEvent`、`dispatchTouchEvent` 与 `onGenericMotionEvent` 进行精准挂钩，确保顶层优先捕获输入事件并阻断宿主内置逻辑干扰。

### 2. 设置界面注入引擎 (`ShortcutSettingsInjector.kt`)

- **菜单挂载**：挂钩 `BaseQuickAdapter.submitList`，在 `SettingsMenuAdapter` 数据源的 `STYLUS`（手写笔）条目上方插入空占位符，并在 `onBindViewHolder` 中渲染对齐原生主题的「快捷键设置」侧栏条目。
- **面板挂载**：在 `SettingsActivity` 右侧内容容器中动态注入配置面板视图，监听 ViewModel 菜单选中状态，实现与原生设置子项一致的显隐切换逻辑。
- **就地按键录制**：采用单行胶囊状交互组件（Pill View），点击后进入录制等待态，通过 Activity 按键拦截器直接捕获物理按键并更新配置，支持自动冲突避让与 Backspace/Delete 清除。

### 3. 画板调度引擎 (`NoteScribbleHook.kt`)

- **高速缓存机制**：
  - `toolViewCache`：维护工具容器（`ll_shape_tool_container`）各子 View 的弱引用映射。
  - `classEnumFieldCache` / `classSelectedFieldCache`：缓存混淆后的工具枚举字段与选中状态布尔字段，避免在主线程按键事件中重复执行类结构反射。
- **重复触发保护**：在执行 `performClick()` 前比对目标工具的选中状态，处于激活态时直接返回，避免触发宿主次级配置浮层。
- **防抖状态机**：
  - **按下（Hold Down）**：记忆当前激活的书写笔工具，切换至橡皮擦工具，取消任何挂起的回退任务。
  - **释放（Hold Up）**：启动 50ms 延时任务。若 50ms 内捕获到落笔或重发脉冲，则保留橡皮擦状态；超时后立即切换回此前记忆的书写笔，消除按键粘滞与误回退。

### 4. 状态与配置持久化 (`ShortcutSettingsState.kt`, `KeyConfig.kt`)

- **内存状态**：使用 `@Volatile` 变量管理已映射键码，保障画板事件分发期间纳秒级无锁读取。
- **配置持久化**：使用 `SharedPreferences` 存储各操作绑定的 Android KeyCode。新键位配置写入时自动校验其他动作项，发生冲突时执行自动解绑。

---

## 支持的操作类型

| 操作类型 | 标识符 | 默认按键 | 说明 |
| :--- | :--- | :--- | :--- |
| 按切松回 | `eraser_hold` | KeyCode 29 (`B`) | 按住切换为橡皮擦，松开回弹画笔 |
| 套索 | `lasso` | KeyCode 33 (`E`) | 一键切换至套索圈选工具 |
| 画笔 | `pen` | 未设置 | 一键切换至普通画笔工具 |
| 橡皮擦 | `eraser` | 未设置 | 一键切换至橡皮擦工具 |

---

## 项目结构

```text
app/src/main/
├── AndroidManifest.xml                  # 模块清单，声明 Xposed 元数据与目标作用域
├── resources/META-INF/xposed/
│   ├── java_init.list                   # Xposed 入口类 (pochita.hook.MainHook)
│   ├── module.prop                      # 模块规范配置 (API 102, protective mode)
│   └── scope.list                       # 作用域声明 (com.onyx.galaxy.note)
├── res/values/
│   ├── strings.xml                      # 模块名称与作用域资源
│   └── themes.xml                       # 基础主题声明
└── java/pochita/
    ├── hook/
    │   ├── MainHook.kt                  # 模块入口、ABI 检测与 Activity 挂钩
    │   ├── NoteScribbleHook.kt          # 画板工具调度、反射缓存与防抖控制器
    │   ├── ShortcutSettingsInjector.kt  # 设置页菜单注入与就地配置面板
    │   └── ShortcutSettingsState.kt     # 快捷键运行时状态与内存缓存
    └── model/
        ├── ActionType.kt                # 快捷键操作定义枚举
        └── KeyConfig.kt                 # 键位持久化与文本格式化
```

---

## 构建与部署

### 构建环境

- JDK 17+
- Android SDK (API 34+)
- LSPosed 框架环境 (API 102+)

### 构建命令

```bash
# 构建 Debug 版本
./gradlew assembleDebug

# 构建 Release 版本
./gradlew assembleRelease
```

输出路径位于 `app/build/outputs/apk/`。

### 安装命令

```bash
# 通过 adb 安装至连接的设备
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

安装完成后在 LSPosed 管理器中启用模块，并重启 StarNote 应用。
