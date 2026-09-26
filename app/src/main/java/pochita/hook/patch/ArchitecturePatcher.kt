package pochita.hook.patch

import android.os.Build
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 运行架构动态兼容补丁
 *
 * 解决 64 位 x86 / x86_64 虚拟化环境（如 WSA）APEX 路径导致误判回退 32 位的问题；
 * 仅在当前设备为 x86 / x86_64 架构时生效，ARM / ARM64 原生真机环境自动安全跳过。
 */
object ArchitecturePatcher {
    private const val TAG = "StarNoteArchPatch"
    private val isHooked = AtomicBoolean(false)

    fun isX86Architecture(): Boolean {
        for (abi in Build.SUPPORTED_ABIS) {
            if (abi.contains("x86", ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    fun apply(module: XposedModule, classLoader: ClassLoader): Boolean {
        if (!isX86Architecture()) {
            Log.i(TAG, "ARM architecture detected (${Build.SUPPORTED_ABIS.firstOrNull()}), skipping x86 patch")
            return true
        }

        if (isHooked.get()) return true

        return try {
            val tClass = classLoader.loadClass("com.sagittarius.v6.b.t")

            val dMethod = tClass.getDeclaredMethod("d")
            module.hook(dMethod)
                .setPriority(XposedInterface.PRIORITY_HIGHEST)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { true }

            val eMethod = tClass.getDeclaredMethod("e")
            module.hook(eMethod)
                .setPriority(XposedInterface.PRIORITY_HIGHEST)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { true }

            isHooked.set(true)
            Log.i(TAG, "Successfully applied architecture compatibility hooks")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to apply architecture compatibility hooks", t)
            false
        }
    }
}
