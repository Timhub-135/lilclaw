package com.lilclaw.app.service.gateway

import android.content.Context
import java.io.File

/**
 * 进程启动引擎的公共契约。lilclaw 有两种实现，按运行环境由 GatewayManager 选用：
 *
 *  - ProcessRunner（PROOT）：原生 AOSP，ptrace + seccomp 硬隔离。
 *  - SandboxProxy（SANDBOX）：卓易通等受限容器，零 ptrace 的沙盒代理层。
 *
 * 两者都负责在 Alpine rootfs 内启动 gateway 与 serve-ui，并管理其生命周期。
 */
interface EngineProxy {
    /** gateway 进程句柄（由实现维护，只读暴露）。 */
    val gatewayProcess: Process?

    /** serve-ui 进程句柄（由实现维护，只读暴露）。 */
    val serveUiProcess: Process?

    /** 在引擎内启动 OpenClaw gateway，返回进程句柄。 */
    suspend fun startGateway(port: Int): Process

    /** 在引擎内启动 serve-ui 静态服务；不存在则返回 null。 */
    fun startServeUi(): Process?

    /** 停止所有受管进程。 */
    fun stopAll()

    /** 强制停止所有受管进程（超时兜底）。 */
    fun forceStopAll()

    companion object {
        /** 构造 rootfs 路径（所有引擎共享）。 */
        fun rootfsDir(context: Context): File = File(context.filesDir, "rootfs")
    }
}
