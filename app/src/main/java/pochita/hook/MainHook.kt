package pochita.hook

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Xposed module for StarNote (com.onyx.galaxy.note) runtime compatibility.
 *
 * Resolves architecture detection issues under 64-bit x86 Android runtimes (e.g. WSA)
 * to ensure normal initialization and startup.
 */
class MainHook : XposedModule() {

    companion object {
        const val TAG = "StarNoteHook"
        private const val TARGET_PACKAGE = "com.onyx.galaxy.note"
        private val isHooked = AtomicBoolean(false)
    }

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        super.onModuleLoaded(param)
        Log.i(TAG, "Module loaded in process: ${param.processName}")
    }

    override fun onPackageReady(param: PackageReadyParam) {
        super.onPackageReady(param)
        if (param.packageName != TARGET_PACKAGE) return

        Log.i(TAG, "Package ready for $TARGET_PACKAGE, applying compatibility hooks")
        applyCompatibilityFix(param.classLoader)
    }

    /**
     * Fixes architecture checks under modern 64-bit x86 runtimes where linker binaries
     * reside in APEX runtime paths, avoiding incorrect fallback to 32-bit binaries.
     */
    private fun applyCompatibilityFix(classLoader: ClassLoader): Boolean {
        if (isHooked.get()) return true

        return try {
            val tClass = classLoader.loadClass("com.sagittarius.v6.b.t")

            // Force 64-bit detection
            val dMethod = tClass.getDeclaredMethod("d")
            hook(dMethod)
                .setPriority(XposedInterface.PRIORITY_HIGHEST)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { true }

            // Ensure x86 architecture check passes
            val eMethod = tClass.getDeclaredMethod("e")
            hook(eMethod)
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

    override fun onHotReloading(param: HotReloadingParam): Boolean = true

    override fun onHotReloaded(param: HotReloadedParam) {
        super.onHotReloaded(param)
        Log.i(TAG, "Module hot reloaded")
    }
}
