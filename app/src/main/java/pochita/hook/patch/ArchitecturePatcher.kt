package pochita.hook.patch

import android.os.Build
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 运行架构动态兼容补丁
 *
 * 针对 x86 / x86_64 虚拟化环境（如 Waydroid、WSA）适配加固壳动态链接库加载流程。
 *
 * 原理说明：
 * 1. 宿主加固壳（Sagittarius）内部通过方法 `com.sagittarius.v6.b.t.e()` 探测 `/system/bin/linker` 的 ELF Header。
 *    在具备 32 位支持的 x86 环境下，该检测命中并返回 true。
 * 2. 壳检测到 x86 特征后，会跳过标准的 `System.loadLibrary`，转而从 assets 中解压 32 位的备用动态库到应用私有目录
 *    （`.x86lib/libbaiduprotect.so`），导致 64 位运行态进程载入时抛出 `UnsatisfiedLinkError`。
 * 3. 通过挂钩 `e()` 强制返回 false，驱动壳进入 `!e()` 判定分支，使用标准 `System.loadLibrary`
 *    直接加载 APK 内置的原生 64 位动态库，并交由底层二进制转译层（如 libndk / libhoudini）执行。
 * 4. 挂钩 `b()` 作为兜底保护，并在模块装载期自动清除残留的陈旧 `.x86lib` 缓存文件。
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
            Log.i(TAG, "Native ARM architecture detected (${Build.SUPPORTED_ABIS.firstOrNull()}), skipping patch")
            return true
        }

        if (isHooked.get()) return true

        cleanupLegacyX86Libs()

        return try {
            val tClass = classLoader.loadClass("com.sagittarius.v6.b.t")

            // 1. 拦截 e()：阻断加固壳进入 x86lib 释放分支
            try {
                val eMethod = tClass.getDeclaredMethod("e")
                module.hook(eMethod)
                    .setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { false }
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to hook t.e: ${t.message}")
            }

            // 2. 拦截 b()：兜底直接通过系统 Linker 加载动态库
            try {
                val bMethod = tClass.getDeclaredMethod("b")
                module.hook(bMethod)
                    .setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        try {
                            System.loadLibrary("baiduprotect")
                        } catch (t: Throwable) {
                            Log.w(TAG, "Direct System.loadLibrary fallback to original loader: ${t.message}")
                            chain.proceed()
                        }
                        null
                    }
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to hook t.b: ${t.message}")
            }

            isHooked.set(true)
            Log.i(TAG, "Successfully installed architecture patch")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to apply architecture patch", t)
            false
        }
    }

    /**
     * 清理应用私有目录下可能遗留的历史 32 位 .x86lib 缓存，防止被未拦截逻辑误用
     */
    private fun cleanupLegacyX86Libs() {
        val targetPaths = listOf(
            "/data/user/0/com.onyx.galaxy.note/.x86lib",
            "/data/data/com.onyx.galaxy.note/.x86lib"
        )
        for (path in targetPaths) {
            try {
                val dir = File(path)
                if (dir.exists()) {
                    dir.deleteRecursively()
                    Log.i(TAG, "Purged stale library cache: $path")
                }
            } catch (t: Throwable) {
                Log.d(TAG, "Unable to purge path $path: ${t.message}")
            }
        }
    }
}
