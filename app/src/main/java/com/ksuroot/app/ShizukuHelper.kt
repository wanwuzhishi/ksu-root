package com.ksuroot.app

import android.content.Context
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import java.util.concurrent.TimeUnit

/** Shizuku API 的极薄封装：只提供本 App 需要的「以 shell 权限跑命令 / 起进程」。 */
object ShizukuHelper {

    const val PERM_REQUEST = 1001

    /**
     * 已知的 Shizuku 兼容管理器。能否真正跑通取决于两件事，缺一不可：
     *
     *   1) 保留名为 `"shizuku"` 的 binder service 和 `IShizukuService` 的 AIDL 全名；
     *   2) 保留 `moe.shizuku.manager.permission.API_V23` 这个权限名——官方
     *      `dev.rikka.shizuku:api` 把该字符串硬编码在 checkPermission 里，改了就授不了权。
     *
     * 下面按「包名 → 兼容情况」标注。只换包名、权限名照抄的能直接跑；连权限名一起换成
     * 自己前缀的，这里只能识别出「装了」，实际会停在未授权。
     */
    private val SHIZUKU_PKGS = listOf(
        "moe.shizuku.privileged.api",   // 官方；yangFenTuoZi/Shizuku、rushiranpise/Shizuku-Next、ShizukuPlus 的 dropin 版同包名
        "com.hamondev.shevery",         // Shevery（权限名保留 moe.*，实测可用）
        "af.shizuku.plus.api",          // ShizukuPlus 独立版（权限名改成 af.shizuku.plus.permission.API_V23）
        "kerneldroid.nightzuku",        // Nightzuku（权限名改成 kerneldroid.nightzuku.permission.API）
    )

    /** 权限名与官方 API 库一致、能真正授权成功的包 */
    private val API_COMPAT_PKGS = setOf(
        "moe.shizuku.privileged.api",
        "com.hamondev.shevery",
    )

    private fun isInstalled(ctx: Context, pkg: String): Boolean = try {
        ctx.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: Throwable) {
        false
    }

    fun isShizukuInstalled(ctx: Context): Boolean = SHIZUKU_PKGS.any { isInstalled(ctx, it) }

    /** 设备上装了哪些已知管理器（用于状态行） */
    fun installedPkgs(ctx: Context): List<String> = SHIZUKU_PKGS.filter { isInstalled(ctx, it) }

    /** 装了，但装的是权限名不兼容的那个版本 */
    fun incompatibleInstall(ctx: Context): Boolean =
        installedPkgs(ctx).let { it.isNotEmpty() && it.none { p -> p in API_COMPAT_PKGS } }

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
