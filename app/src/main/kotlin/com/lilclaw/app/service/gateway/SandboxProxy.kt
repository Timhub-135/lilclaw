package com.lilclaw.app.service.gateway

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * SandboxProxy — lilclaw 的沙盒代理层（卓易通 / 受限容器版启动引擎）。
 *
 * 在无 ptrace（鸿蒙卓易通 iSulad 容器）、无 CAP_SYS_CHROOT / CAP_SYS_ADMIN / seccomp-filter 的
 * 环境下，替代 proot：通过"命令转义 + 路径守卫 + 环境锚定"让 agent 的运行全部落在 Alpine
 * rootfs 内，形成"伪独立沙盒"。接口与 [ProcessRunner] 对齐，可由 `EngineDetector` 选用。
 *
 *   - 命令锚定：argv 直接用 rootfs 内 arm64 二进制；cwd = rootfs/root/workspace
 *   - 环境锚定：ProcessBuilder.environment() 清空后重建，PATH/LD/HOME/TMP 全指 rootfs
 *   - 路径守卫：agent 传人的路径经 guardPath() 重写进 rootfs，拒绝 ../、/proc 等越界
 *   - 装包锚定：apk --root / pip --target / npm --prefix 全部只写 rootfs
 *
 * 边界：这是控制面/伪独立隔离，不是内核级 chroot（卓易通无 chroot/mount-ns/seccomp-filter）。
 * 保证 "agent 能表达的操作都发生在 rootfs"；进程内嵌的恶意绝对路径访问，需在原生 Android
 * 的 seccomp 环境下用 proot 硬隔离兜底。
 *
 * Implements [EngineProxy] (SANDBOX engine).
 */
class SandboxProxy(private val context: Context) : EngineProxy {

    companion object {
        private const val TAG = "SandboxProxy"

        // Alpine apk 国内镜像（华为云）
        private const val ALPINE_MAIN =
            "https://mirrors.huaweicloud.com/alpine/v3.21/main\n"
        private const val ALPINE_COMMUNITY =
            "https://mirrors.huaweicloud.com/alpine/v3.21/community\n"

        // Python (pip) 国内镜像：清华 TUNA
        private const val PIP_INDEX_URL = "https://pypi.tuna.tsinghua.edu.cn/simple"

        // Node (npm) 国内镜像：npmmirror
        private const val NPM_REGISTRY_URL = "https://registry.npmmirror.com"
    }

    val rootfsDir: File get() = File(context.filesDir, "rootfs")
    private val wsDir: File get() = File(rootfsDir, "root/workspace")
    private val tmpDir: File get() = File(rootfsDir, "tmp")

    override var gatewayProcess: Process? = null
        private set
    override var serveUiProcess: Process? = null
        private set

    init {
        Log.i(TAG, "SandboxProxy ready (no-ptrace engine). rootfs=${rootfsDir.absolutePath}")
        ensureMirrorConfig()
    }

    /**
     * 在 rootfs 内写国内镜像配置文件，让沙盒里直接跑的 pip/npm 也走国内源：
     *  - /root/.npmrc                → npm 用 npmmirror 国内源
     *  - /root/.config/pip/pip.conf   → pip 用清华国内源
     * 仅在文件不存在时写入（幂等）。
     */
    fun ensureMirrorConfig() {
        val root = rootfsDir
        try {
            val npmrc = File(root, "root/.npmrc")
            if (!npmrc.exists()) {
                npmrc.parentFile?.mkdirs()
                npmrc.writeText("registry=$NPM_REGISTRY_URL\n")
            }
            val pipConf = File(root, "root/.config/pip/pip.conf")
            if (!pipConf.exists()) {
                pipConf.parentFile?.mkdirs()
                pipConf.writeText(
                    "[global]\n" +
                    "index-url=$PIP_INDEX_URL\n" +
                    "trusted-host=pypi.tuna.tsinghua.edu.cn\n"
                )
            }
            Log.i(TAG, "Mirror config ensured (npm=$NPM_REGISTRY_URL, pip=$PIP_INDEX_URL)")
        } catch (e: Exception) {
            Log.w(TAG, "ensureMirrorConfig failed: ${e.message}")
        }
    }

    // ── 进程启动：rootfs 锚定 exec arm64 二进制，不经 proot ──

    /**
     * Start the OpenClaw gateway process via sandbox (no ptrace).
     */
    override suspend fun startGateway(port: Int): Process = withContext(Dispatchers.IO) {
        val argv = listOf(
            "node", "/usr/local/bin/openclaw", "gateway", "run",
            "--allow-unconfigured", "--port", port.toString(), "--token", "lilclaw-local"
        )
        val process = spawnSandboxed(argv)
        gatewayProcess = process
        process
    }

    /**
     * Start the serve-ui.cjs static file server via sandbox.
     */
    override fun startServeUi(): Process? {
        if (serveUiProcess?.isAlive == true) return serveUiProcess
        if (!File(rootfsDir, "root/lilclaw-ui/serve-ui.cjs").exists()) {
            Log.w(TAG, "serve-ui.cjs not found, skip")
            return null
        }
        val process = spawnSandboxed(listOf("node", "/root/lilclaw-ui/serve-ui.cjs"))
        serveUiProcess = process
        return process
    }

    override fun stopAll() {
        serveUiProcess?.destroy(); serveUiProcess = null
        gatewayProcess?.destroy(); gatewayProcess = null
    }

    override fun forceStopAll() {
        serveUiProcess?.let { if (it.isAlive) it.destroyForcibly() }; serveUiProcess = null
        gatewayProcess?.let { if (it.isAlive) it.destroyForcibly() }; gatewayProcess = null
    }

    // ── 唯一命令通道：agent 的所有命令走这里 ──

    /**
     * 在沙盒内执行一条 agent 命令 argv。返回进程，调用方负责 pump stdout/stderr。
     */
    suspend fun exec(args: List<String>): Process = withContext(Dispatchers.IO) {
        spawnSandboxed(args)
    }

    /**
     * 转义：把 agent 的命令命令行安全拆成 argv（去 shell 元字符，最小实现）。
     */
    fun escape(rawCommandLine: String): List<String> {
        return rawCommandLine
            .replace("`", "")
            .replace("$(", "")
            .trim()
            .split("\\s+".toRegex())
            .filter { it.isNotEmpty() }
    }

    /**
     * 路径守卫：把 agent 表达的路径锚定到 rootfs。越界即抛 SecurityException。
     */
    fun guardPath(p: String): String {
        val denied = p.contains("../") || p.endsWith("/..") || isHostPath(p)
        if (denied) throw SecurityException("SandboxProxy: path denied: $p")
        return if (p.startsWith("/")) {
            File(rootfsDir, p.trimStart('/')).absolutePath
        } else {
            File(wsDir, p).absolutePath
        }
    }

    /** 是否指向宿主敏感根（越界路径）。 */
    private fun isHostPath(p: String): Boolean {
        val deny = listOf("/proc/", "/sys/", "/dev/", "/data/", "/sdcard/", "/storage/", "/system/")
        return deny.any { p.startsWith(it) }
    }

    // ── 装包锚定：只写 rootfs ──

    /** apk add —— --root 强制进 rootfs。 */
    suspend fun apkAdd(pkg: String) = withContext(Dispatchers.IO) {
        val repos = File(rootfsDir, "etc/apk/repositories")
        repos.parentFile?.mkdirs()
        if (!repos.exists()) repos.writeText(ALPINE_MAIN + ALPINE_COMMUNITY)
        val pb = ProcessBuilder(
            "/sbin/apk", "--root", rootfsDir.absolutePath,
            "--repositories-file", repos.absolutePath,
            "--update-cache", "--allow-untrusted", "add", pkg
        ).redirectErrorStream(true)
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        val code = p.waitFor()
        if (code != 0) Log.w(TAG, "apk add $pkg failed($code): ${out.take(300)}")
        else Log.i(TAG, "apk add $pkg ok")
    }

    /** pip install —— --target 强制进 rootfs 工作区 vendor，用清华 pip 镜像。 */
    suspend fun pipInstall(pkg: String): Process =
        spawnSandboxed(
            listOf(
                "/usr/bin/python3", "-m", "pip", "install",
                "--target", "vendor", "-i", PIP_INDEX_URL, pkg,
            )
        )

    /** npm —— 依赖 spawnSandboxed 注入的 npm_config_prefix + npm_config_registry（都指向国内/rootfs）。 */
    suspend fun npm(args: List<String>): Process =
        spawnSandboxed(listOf("/usr/bin/npm", *args.toTypedArray()))

    // ── 内部：rootfs 锚定的 spawn ──

    /**
     * 核心：用 ProcessBuilder 在 rootfs 工作区内启动命令。
     * - argv 直接是 rootfs 内绝对路径命令
     * - environment() 清空重建成 rootfs 锚定环境（不继承宿主）
     * - 注入 android-compat 垫片所需的 NODE_OPTIONS（不依赖 ptrace，处理 Android /proc/net 限制）
     */
    private fun spawnSandboxed(argv: List<String>): Process {
        wsDir.mkdirs(); tmpDir.mkdirs()
        val root = rootfsDir.absolutePath
        val pb = ProcessBuilder(argv).directory(wsDir).redirectErrorStream(true)

        val env = pb.environment()
        env.clear()
        env["HOME"] = "$root/root"
        env["TMPDIR"] = "$root/tmp"
        env["TEMP"] = "$root/tmp"
        env["TMP"] = "$root/tmp"
        env["PATH"] = listOf(
            "$root/usr/local/bin", "$root/usr/local/sbin", "$root/usr/bin",
            "$root/usr/sbin", "$root/bin", "$root/sbin"
        ).joinToString(":")
        env["LD_LIBRARY_PATH"] = "$root/lib:$root/usr/lib"
        env["PYTHONPATH"] = "$root/usr/lib/python3.12/site-packages"
        env["PYTHONUSERBASE"] = "$root/root/.local"
        env["PIP_TARGET"] = "$root/root/.pip-target"
        env["PIP_INDEX_URL"] = PIP_INDEX_URL
        env["PIP_TRUSTED_HOST"] = "pypi.tuna.tsinghua.edu.cn"
        env["npm_config_prefix"] = "$root/root/.npm-global"
        env["NPM_CONFIG_PREFIX"] = "$root/root/.npm-global"
        env["npm_config_registry"] = NPM_REGISTRY_URL
        env["NPM_CONFIG_REGISTRY"] = NPM_REGISTRY_URL
        // 关键：sandbox 无 chroot 路径翻译，node 看到的是真实绝对路径。
        // NODE_OPTIONS 必须用 rootfs 内 android-compat.cjs 的真实绝对路径，而非 proot 的虚拟 /root/...
        env["NODE_OPTIONS"] = "--require $root/root/android-compat.cjs"

        // 保证每个进程的 NODE_OPTIONS 只对 node 生效；对其他命令它会被忽略，无害
        return pb.start()
    }
}
