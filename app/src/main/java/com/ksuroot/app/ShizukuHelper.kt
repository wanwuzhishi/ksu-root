package com.ksuroot.app

import android.content.Context
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import java.util.concurrent.TimeUnit

/** Shizuku API 的极薄封装：只提供本 App 需要的「以 shell 权限跑命令 / 起进程」。 */
object ShizukuHelper {

    const val PERM_REQUEST = 1001

    /**
     * 已知的 Shizuku 兼容管理器。Shevery 是 Shizuku 的 fork，保留了同名 binder service
     * 与 `moe.shizuku.manager.permission.API_V23` 权限，只换了 App 包名。两者只能装一个。
     */
    private val SHIZUKU_PKGS = listOf(
        "moe.shizuku.privileged.api",   // 官方
        "com.hamondev.shevery",         // Shevery
    )

    fun isShizukuInstalled(ctx: Context): Boolean = SHIZUKU_PKGS.any { pkg ->
        try {
            ctx.packageManager.getPackageInfo(pkg, 0)
            true
        } catch (e: Throwable) {
            false
        }
    }

    fun isBinderAlive(): Boolean = try {
        Shizuku.pingBinder()
    } catch (e: Throwable) {
        false
    }

    fun hasPermission(): Boolean = try {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) {
        false
    }

    /** 未授权则弹授权框；已授权返回 true。 */
    fun requestPermission(requestCode: Int = PERM_REQUEST): Boolean = try {
        when {
            !isBinderAlive() -> false
            hasPermission() -> true
            else -> {
                Shizuku.requestPermission(requestCode)
                false
            }
        }
    } catch (e: Throwable) {
        false
    }

    fun newProcess(cmd: Array<String>): Process = Shizuku.newProcess(cmd, null, null)

    /**
     * 同步执行一条 shell 命令，返回合并后的输出。
     *
     * 踩了三次坑，最后只保留「读流」这一条路：
     * 1. 先 readText() 完再 waitFor —— Shizuku 远程流在进程还没写完就 EOF，拿到空串。
     * 2. start 线程后立刻 waitFor —— 命令比线程调度快，进程一退流就关，copyTo 抛异常，又是空串。
     * 3. 调 waitFor / exitValue —— ShizukuRemoteProcess 会抛 IllegalThreadStateException
     *    "process hasn't exited"，异常被吞成 "ERROR: ..." 字符串，调用方解析不出数字。
     * 所以：两个流各起线程立刻开读，只靠 join(流 EOF) 判断结束，不碰 waitFor / exitValue。
     */
    fun exec(cmd: String, timeoutSec: Long = 20): String = try {
        val p = newProcess(arrayOf("sh", "-c", cmd))
        val out = java.io.ByteArrayOutputStream()
        val err = java.io.ByteArrayOutputStream()
        val tOut = Thread { try { p.inputStream.copyTo(out) } catch (_: Throwable) {} }
        val tErr = Thread { try { p.errorStream.copyTo(err) } catch (_: Throwable) {} }
        tOut.start()
        tErr.start()
        tOut.join(timeoutSec * 1000)
        tErr.join(2000)
        if (tOut.isAlive || tErr.isAlive) {
            try {
                p.destroyForcibly()
            } catch (_: Throwable) {
            }
        }
        (out.toString("UTF-8") + "\n" + err.toString("UTF-8")).trim()
    } catch (e: Throwable) {
        "ERROR: ${e.message}"
    }
}
