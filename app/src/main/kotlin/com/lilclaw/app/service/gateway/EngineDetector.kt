package com.lilclaw.app.service.gateway

import android.content.Context
import android.util.Log
import java.io.File

/**
 * EngineDetector — 给出初始启动引擎的保守选择。
 *
 * 原生 AOSP Android：proot（ptrace + seccomp）可用且更强，优先 proot。
 * 鸿蒙卓易通等受限容器：ptrace 被禁，proot 起不来，用 [SandboxProxy]。
 *
 * 探测方法诚实说明：
 *  - Java/Android 层无法可靠地做 fork()+PTRACE_TRACEME 自检（纯 Java 不暴露 ptrace）。
 *  - 因此这里用一个轻量启发式：proot 二进制（libproot.so）是否随 APK 打包，作为
 *    "偏向原生 AOSP 还是受限容器"的初始信号。
 *  - **真正的裁决交给 GatewayManager 的运行期降级**：proot 引擎启动即崩（进程秒退）时，
 *    自动切换到 sandbox 引擎。EngineDetector 只负责给出初始优先顺序，即便判错也能自我纠正。
 */
object EngineDetector {

    private const val TAG = "EngineDetector"

    enum class Engine { PROOT, SANDBOX }

    @Volatile
    private var cached: Engine? = null

    /**
     * 初始引擎选择（结果缓存）。仅作启动初选，最终以 GatewayManager 运行期降级为准。
     */
    fun detect(context: Context): Engine {
        cached?.let { return it }

        val engine = if (ptraceLikelyAvailable(context)) {
            Log.i(TAG, "→ PROOT engine (initial)")
            Engine.PROOT
        } else {
            Log.w(TAG, "→ SANDBOX engine (initial, 疑似受限容器)")
            Engine.SANDBOX
        }

        cached = engine
        return engine
    }

    /** 强制重测（如用户切换环境）。 */
    fun reset() {
        cached = null
    }

    /**
     * 判断 ptrace 大概率可用。启发式：
     *  1. proot 是否随 APK 打包（libproot.so 存在于 jniLibs）——原生 AOSP 构建才有。
     *  2. 设备是否有用户可覆盖的"强制沙盒"设置（可扩展）。
     * 判定失败时倾向 SANDBOX（更保守，避免在受限容器里反复尝试失败的 proot）。
     */
    private fun ptraceLikelyAvailable(context: Context): Boolean {
        val nativeLibDir = File(context.applicationInfo.nativeLibraryDir)
        val prootBin = File(nativeLibDir, "libproot.so")
        if (!prootBin.exists()) {
            Log.i(TAG, "libproot.so not bundled → sandbox preferred")
            return false
        }
        // 有 proot 二进制 → 偏向 proot（原生 AOSP）。运行期失败仍会由 GatewayManager 降级。
        return true
    }
}
