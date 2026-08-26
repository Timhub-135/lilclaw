package com.lilclaw.app.service

import android.content.Context
import android.util.Log
import com.lilclaw.app.service.gateway.ConfigWriter
import com.lilclaw.app.service.gateway.EngineDetector
import com.lilclaw.app.service.gateway.EngineProxy
import com.lilclaw.app.service.gateway.ProcessRunner
import com.lilclaw.app.service.gateway.RootfsManager
import com.lilclaw.app.service.gateway.SandboxProxy
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

sealed class GatewayState {
    data object Idle : GatewayState()
    data object Preparing : GatewayState()     // extracting bundled rootfs
    data object Downloading : GatewayState()   // downloading layers (fallback/update)
    data object Extracting : GatewayState()
    data object Starting : GatewayState()      // gateway process starting
    data object WaitingForUi : GatewayState()  // gateway up, polling serve-ui
    data object Running : GatewayState()       // both gateway + serve-ui ready
    data class Error(val message: String) : GatewayState()
}

/**
 * Orchestrates the gateway lifecycle: rootfs setup, config, process start/stop.
 * Delegates heavy lifting to [RootfsManager], ProcessRunner/SandboxProxy, [ConfigWriter].
 *
 * 引擎选择：启动时用 [EngineDetector] 探测 ptrace 是否可用——
 *   - 原生 AOSP（ptrace 可用）→ [ProcessRunner]（proot，硬隔离）
 *   - 卓易通等受限容器 → [SandboxProxy]（零 ptrace 沙盒代理层）
 * 若首选引擎启动失败，自动降级到另一引擎（"处理入口托管"而非硬报错）。
 */
class GatewayManager(private val context: Context) {

    companion object {
        private const val TAG = "GatewayManager"

        /** 距上次层更新检查超过此时间才联网检查（默认 1 天）。避免启动即联网。 */
        private const val UPDATE_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val rootfs = RootfsManager(context)

    /** 两个引擎都保留引用，便于探测 + 失败降级。 */
    private val prootEngine = ProcessRunner(context)
    private val sandboxEngine = SandboxProxy(context)

    /** 当前激活的引擎。 */
    private var activeEngine: EngineProxy = prootEngine

    private val _state = MutableStateFlow<GatewayState>(GatewayState.Idle)
    val state: StateFlow<GatewayState> = _state

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress

    private val _logLines = MutableSharedFlow<String>(replay = 50, extraBufferCapacity = 200)
    val logLines: SharedFlow<String> = _logLines

    val isRootfsReady: Boolean get() = rootfs.isReady

    private fun log(msg: String) {
        Log.i(TAG, msg)
        _logLines.tryEmit(msg)
    }

    // ── 离线优先的层更新检查 ─────────────────────────────

    /**
     * 是否值得联网检查层更新。为避免"安装后启动即联网下载"拖慢甚至阻塞启动：
     *  - 首次安装（无 .layers.json）或 rootfs 不完整：需要联网获取清单。
     *  - 已就绪的安装：默认跳过联网更新，除非距上次检查超过 [UPDATE_CHECK_INTERVAL_MS]。
     * 返回 true 表示调用方应联网检查更新。
     */
    private fun shouldCheckForUpdates(): Boolean {
        return try {
            if (!rootfs.isReady) return true
            val layersJson = rootfs.layersJsonFile()
            if (!layersJson.exists()) return true
            val last = layersJson.lastModified()
            (System.currentTimeMillis() - last) > UPDATE_CHECK_INTERVAL_MS
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Full bootstrap: extract rootfs → write config → start gateway → poll UI ready.
     */
    fun bootstrap(
        provider: String,
        apiKey: String,
        model: String,
        port: Int = 3000,
        onReady: () -> Unit,
    ) {
        scope.launch {
            try {
                GatewayService.start(context)

                // Phase 1: Extract rootfs
                if (!rootfs.isReady) {
                    _state.value = GatewayState.Preparing
                    _progress.value = 0f
                    GatewayService.updateStatus(context, "正在准备...")

                    rootfs.extractAll(
                        onStateChange = { phase ->
                            _state.value = when (phase) {
                                "downloading" -> GatewayState.Downloading
                                "extracting" -> GatewayState.Extracting
                                else -> GatewayState.Preparing
                            }
                        },
                        log = ::log,
                    )
                }

                // Collect rootfs progress
                launch { rootfs.progress.collect { _progress.value = it } }

                // Phase 2: Write config
                log("写入配置...")
                ConfigWriter.writeGatewayConfig(rootfs.rootfsDir, port, provider, apiKey, model)
                log("配置完成 ✓")
                _progress.value = 0.8f

                // Phase 3: Start processes
                _state.value = GatewayState.Starting
                GatewayService.updateStatus(context, "正在启动引擎...")
                startProcesses(port)

                // Phase 4-5: Wait for ports
                waitForReady(port, onReady)

            } catch (e: Exception) {
                Log.e(TAG, "Bootstrap failed", e)
                log("错误: ${e.message}")
                _state.value = GatewayState.Error(e.message ?: "未知错误")
            }
        }
    }

    /**
     * Quick start for app restart (rootfs already extracted, config already written).
     */
    fun quickStart(
        provider: String = "",
        apiKey: String = "",
        model: String = "",
        port: Int = 3000,
        onReady: () -> Unit = {},
    ) {
        if (_state.value == GatewayState.Running) return
        scope.launch {
            try {
                GatewayService.start(context)

                if (!rootfs.isReady) {
                    _state.value = GatewayState.Error("环境未就绪")
                    return@launch
                }

                // Check for layer updates —— 离线优先：仅当 rootfs 不完整或距上次检查超过阈值才联网
                // （安装后 rootfs 完整+近期已检查 → 跳过联网，启动零下载/零等待）
                if (shouldCheckForUpdates()) {
                    val stale = rootfs.getStaleLayers(::log)
                    if (stale.isNotEmpty()) {
                        log("正在更新 ${stale.joinToString { it.name }}...")
                        rootfs.updateLayers(stale, ::log)
                    } else {
                        rootfs.layersJsonFile().setLastModified(System.currentTimeMillis())
                    }
                }

                if (provider.isNotEmpty()) {
                    ConfigWriter.writeGatewayConfig(rootfs.rootfsDir, port, provider, apiKey, model)
                }

                // Start processes
                _state.value = GatewayState.Starting
                _progress.value = 0.3f
                GatewayService.updateStatus(context, "正在启动引擎...")
                rootfs.ensureExecutable()
                startProcesses(port)

                // Wait for ports
                waitForReady(port, onReady)

            } catch (e: Exception) {
                Log.e(TAG, "Quick start failed", e)
                log("错误: ${e.message}")
                _state.value = GatewayState.Error(e.message ?: "未知错误")
            }
        }
    }

    fun stop() {
        activeEngine.stopAll()
        scope.launch {
            delay(5000)
            activeEngine.forceStopAll()
        }
        _state.value = GatewayState.Idle
        GatewayService.stop(context)
    }

    fun restart(port: Int = 3000, provider: String = "", apiKey: String = "", model: String = "") {
        stop()
        scope.launch {
            delay(1000)
            quickStart(port = port, provider = provider, apiKey = apiKey, model = model)
        }
    }

    // ── Private helpers ───────────────────────────────────

    /**
     * 按探测结果选择引擎并启动 gateway + serve-ui。
     * 若首选引擎（proot）启动即崩，自动降级到沙盒引擎（卓易通等受限容器）。
     */
    private suspend fun startProcesses(port: Int) {
        // 选择初始引擎
        val preferred = if (EngineDetector.detect(context) == EngineDetector.Engine.PROOT)
            prootEngine else sandboxEngine
        activeEngine = preferred

        log("启动 AI 引擎（${engineName(activeEngine)}）...")
        var gwProcess = try {
            activeEngine.startGateway(port)
        } catch (e: Exception) {
            Log.w(TAG, "engine start failed, failing over: ${e.message}")
            null
        }

        // proot 启动后立刻退出（子进程起不来）→ 降级到 sandbox
        if (gwProcess == null || !gwProcess.isAlive || gwProcess.exitValueSaved()) {
            if (activeEngine === prootEngine) {
                log("proot 引擎不可用，降级到沙盒引擎（卓易通/受限容器模式）...")
                activeEngine = sandboxEngine
                gwProcess = activeEngine.startGateway(port)
            }
        }
        val gw = gwProcess ?: throw IllegalStateException("引擎启动失败：${engineName(activeEngine)}")
        pumpProcessLog(gw, "gw")

        log("启动聊天界面...")
        val uiProcess = activeEngine.startServeUi()
        if (uiProcess != null) {
            pumpProcessLog(uiProcess, "ui")
            log("聊天界面已启动")
        } else {
            log("serve-ui.cjs 未找到，跳过聊天界面")
        }
    }

    /** 进程是否已退出（非 null 且已结束）。 */
    private fun Process.exitValueSaved(): Boolean =
        runCatching { !isAlive }.getOrDefault(true)

    private fun engineName(e: EngineProxy): String =
        if (e === prootEngine) "proot" else "sandbox"

    private fun pumpProcessLog(process: Process, prefix: String) {
        scope.launch {
            process.inputStream?.bufferedReader()?.use { reader ->
                reader.lineSequence().forEach { line ->
                    Log.d(TAG, "[$prefix] $line")
                    if (prefix == "gw" && (
                        line.contains("listening") || line.contains("error", ignoreCase = true)
                        || line.contains("ready") || line.contains("started")
                        || line.contains("config") || line.contains("agent")
                    )) {
                        _logLines.tryEmit(line.take(120))
                    }
                }
            }
        }
    }

    private suspend fun waitForReady(port: Int, onReady: () -> Unit) {
        // Start serve-ui polling FIRST — it's fast (~2s)
        // The SPA handles offline gateway gracefully
        log("等待界面就绪...")
        waitForPort(3001, timeoutMs = 30_000)
        _progress.value = 0.7f
        log("界面就绪，加载中...")

        // Signal that UI is ready (WebView can load now!)
        _state.value = GatewayState.WaitingForUi
        onReady()

        // Now wait for gateway in background
        log("等待引擎就绪...")
        waitForPort(port, timeoutMs = 60_000)
        _progress.value = 1f

        log("就绪")
        _state.value = GatewayState.Running
        GatewayService.updateStatus(context, "运行中")
        onReady()

        // Start monitoring gateway process for crash recovery
        monitorGateway(port)
    }

    private suspend fun waitForPort(port: Int, timeoutMs: Long) = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastError: String? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                val conn = URL("http://127.0.0.1:$port/").openConnection() as HttpURLConnection
                conn.connectTimeout = 2000
                conn.readTimeout = 2000
                conn.requestMethod = "GET"
                val code = conn.responseCode
                conn.disconnect()
                if (code in 100..499) {
                    log("Port $port ready (HTTP $code)")
                    return@withContext
                }
                lastError = "HTTP $code"
            } catch (e: Exception) {
                lastError = e.message
            }
            delay(500)
        }
        throw RuntimeException("Port $port not ready after ${timeoutMs / 1000}s: $lastError")
    }

    /**
     * Monitor gateway process health. If it dies, auto-restart up to 3 times.
     */
    private fun monitorGateway(port: Int) {
        scope.launch {
            var restartCount = 0
            val maxRestarts = 3
            while (restartCount < maxRestarts) {
                delay(5000) // Check every 5 seconds
                val process = activeEngine.gatewayProcess
                if (process == null || !process.isAlive) {
                    if (_state.value == GatewayState.Idle) break // Intentional stop
                    restartCount++
                    log("引擎崩溃，正在重启 ($restartCount/$maxRestarts)...")
                    _state.value = GatewayState.Starting
                    GatewayService.updateStatus(context, "Restarting... ($restartCount/$maxRestarts)")
                    try {
                        val gwProcess = activeEngine.startGateway(port)
                        pumpProcessLog(gwProcess, "gw")
                        waitForPort(port, timeoutMs = 30_000)
                        _state.value = GatewayState.Running
                        GatewayService.updateStatus(context, "运行中")
                        log("Gateway restarted successfully")
                        restartCount = 0 // Reset counter on success
                    } catch (e: Exception) {
                        log("Restart failed: ${e.message}")
                        _state.value = GatewayState.Error("引擎崩溃 ($restartCount/$maxRestarts)")
                    }
                }
            }
        }
    }
}
