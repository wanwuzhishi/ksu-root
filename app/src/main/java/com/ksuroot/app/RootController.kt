package com.ksuroot.app

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 把电脑上验证过的 adb 流程搬到手机上：
 *   部署载荷 -> 布防 kprobe -> 跑 exploit（ptyrun 提供 pty）-> 向 root shell 注入 ksud late-load -> 验证 su
 * 只依赖 Shizuku 的 shell 权限（uid=2000），与 adb shell 完全等价。
 *
 * 载荷分两类：
 *   - 随包分发：pk64560（Apache-2.0）、ptyrun（本项目自写）、两个 shell 脚本
 *   - 运行时获取：ksud —— 从用户已装的 KernelSU 管理器 APK 里抽 libksud.so，
 *     避免把 GPL-3.0 二进制打进本仓库
 */
object RootController {

    const val WORK_DIR = "/data/local/tmp/ksu-root"
    private val ASSETS = listOf("pk64560", "ptyrun", "kp_arm.sh", "ksu_load_setup.sh")
    private const val KSU_PKG = "me.weishu.kernelsu"
    private const val KSUD_MIN_BYTES = 4_000_000L
    private const val EXPLOIT_BUDGET_MS = 8 * 60_000L

    data class Status(
        val shizuku: String,
        val root: Boolean,
        val module: String,
        val selinux: String,
        val suId: String,
    )

    suspend fun checkStatus(ctx: Context): Status = withContext(Dispatchers.IO) {
        val shizuku = when {
            !ShizukuHelper.isShizukuInstalled(ctx) -> "未安装"
            ShizukuHelper.incompatibleInstall(ctx) -> "版本不兼容"
            !ShizukuHelper.isBinderAlive() -> "未运行"
            !ShizukuHelper.hasPermission() -> "未授权"
            else -> "已就绪"
        }
        val module = ShizukuHelper.exec("grep -i kernelsu /proc/modules")
        val selinux = ShizukuHelper.exec("getenforce")
        val suId = ShizukuHelper.exec("su -c id 2>&1")
        Status(shizuku, suId.contains("uid=0"), module, selinux, suId)
    }

    // ---------------------------------------------------------------- 断点续跑

    private const val PREF = "ksu_root_state"
    private const val KEY_RUNNING = "running"
    private const val KEY_ELAPSED = "elapsed"
    private const val KEY_STEP = "step"
    private const val KEY_TIME = "time"

    /** NONE=没有未完成任务，REBOOTED=上次被整机重启打断，KILLED=上次进程被杀但设备没重启 */
    enum class Pending { NONE, REBOOTED, KILLED }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun markRunning(ctx: Context, step: String) {
        prefs(ctx).edit()
            .putBoolean(KEY_RUNNING, true)
            .putLong(KEY_ELAPSED, SystemClock.elapsedRealtime())
            .putString(KEY_STEP, step)
            .putLong(KEY_TIME, System.currentTimeMillis())
            .apply()
    }

    fun markStep(ctx: Context, step: String) {
        prefs(ctx).edit()
            .putString(KEY_STEP, step)
            .putLong(KEY_ELAPSED, SystemClock.elapsedRealtime())
            .apply()
    }

    fun clearRunning(ctx: Context) {
        prefs(ctx).edit().putBoolean(KEY_RUNNING, false).apply()
    }

    fun pending(ctx: Context): Pending {
        val p = prefs(ctx)
        if (!p.getBoolean(KEY_RUNNING, false)) return Pending.NONE
        val saved = p.getLong(KEY_ELAPSED, 0L)
        // elapsedRealtime 从开机算起；当前值反而变小 => 中间发生过重启
        return if (SystemClock.elapsedRealtime() < saved) Pending.REBOOTED else Pending.KILLED
    }

    fun lastStep(ctx: Context): String = prefs(ctx).getString(KEY_STEP, "") ?: ""

    fun lastTime(ctx: Context): Long = prefs(ctx).getLong(KEY_TIME, 0L)

    // ---------------------------------------------------------------- 日志导出

    /** 写到系统「下载」目录，返回可读路径；失败返回 null。 */
    fun exportLog(ctx: Context, text: String): String? {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val name = "ksu-root-$stamp.txt"
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = ctx.contentResolver
                    .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                "下载/$name"
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                dir.mkdirs()
                val f = File(dir, name)
                f.writeText(text)
                f.absolutePath
            }
        } catch (e: Throwable) {
            null
        }
    }

    // ---------------------------------------------------------------- 主流程

    suspend fun enableRoot(ctx: Context, log: (String) -> Unit): Boolean = withContext(Dispatchers.IO) {
        markRunning(ctx, "启动")
        try {
            runSteps(ctx, log)
        } finally {
            // 正常返回（成功/失败）都清掉；panic 重启时进程被杀，这里不会执行，标记就留下来了
            clearRunning(ctx)
        }
    }

    private fun runSteps(ctx: Context, log: (String) -> Unit): Boolean {
        fun step(s: String) {
            markStep(ctx, s)
            log(s)
        }

        step("[1/5] 检查 Shizuku ...")
        if (!ShizukuHelper.isBinderAlive()) {
            log("  Shizuku 未运行，请先在 Shizuku App 中启动服务")
            return false
        }
        if (!ShizukuHelper.hasPermission()) {
            log("  Shizuku 未授权，请点「开启 root」后在弹窗中允许")
            return false
        }
        log("  OK")

        // stage0 依赖几个线程的精确竞争时序。系统负载高的时候调度抖动大，
        // 竞争窗口错位，rb_erase 就会拿一棵对不上的红黑树去改，直接 panic。
        // 实测过三次 panic 都发生在开机后 90-100 秒、load average 20+ 的时候。
        // 这里只提醒，不拦截 —— 用户可能就是想试。
        val uptime = ShizukuHelper.exec("cut -d. -f1 /proc/uptime").trim().toIntOrNull() ?: 0
        val load1 = ShizukuHelper.exec("cut -d' ' -f1 /proc/loadavg").trim().toFloatOrNull() ?: 0f
        if (uptime < 300 || load1 > 8f) {
            log("  注意：设备启动 ${uptime}s、1 分钟负载 ${load1}，系统还在忙。")
            log("  此时跑失败率高（stage0 竞争窗口会被调度抖动打偏，失败就是 panic）。")
            log("  建议等到开机 5 分钟以上、负载降到 5 以下再试。")
        }

        step("[2/5] 部署载荷到 $WORK_DIR ...")
        if (!ensureFiles(ctx, log)) return false
        // 上游仓库自带的 kernelsu-prague.ko 是 uapi=2 的旧模块。exploit 内部硬编码了
        // KSU_MODULE_PATH 指向它，一旦加载成功，管理器会报「需要更新内核」且 su 拿不到。
        // 没法改 exploit 参数，只能让这个路径读不到。
        val legacy = ShizukuHelper.exec("ls /data/local/tmp/kernelsu-prague.ko 2>/dev/null").trim()
        if (legacy.isNotEmpty()) {
            ShizukuHelper.exec("mv /data/local/tmp/kernelsu-prague.ko /data/local/tmp/kernelsu-prague.ko.disabled")
            log("  已把旧 kernelsu-prague.ko 改名为 .disabled（uapi=2，会顶掉新版模块）")
        }

        step("[3/5] 布防 kprobe ...")
        log(ShizukuHelper.exec("sh $WORK_DIR/kp_arm.sh", 40))
        val armed = ShizukuHelper.exec("grep -c window0 /sys/kernel/tracing/kprobe_events").trim()
        val tracing = ShizukuHelper.exec("cat /sys/kernel/tracing/tracing_on").trim()
        if (armed != "1" || tracing != "1") {
            log("  布防失败: window0=$armed tracing_on=$tracing")
            return false
        }
        log("  布防成功 window0=1 tracing_on=1")

        step("[4/5] 运行 exploit（约 2-5 分钟，期间可能重启）...")
        runExploit(log)

        step("[5/5] 验证 su ...")
        val id = ShizukuHelper.exec("su -c id 2>&1")
        log(id)
        return id.contains("uid=0")
    }

    /** 关 root 的唯一可靠方式：重启。stage0 改过内核 random_table，无法热恢复。 */
    suspend fun disableRoot(ctx: Context, log: (String) -> Unit) = withContext(Dispatchers.IO) {
        clearRunning(ctx)
        log("重启中，设备将在 1 分钟内恢复无 root 状态 ...")
        ShizukuHelper.exec("reboot", 10)
    }

    private fun ensureFiles(ctx: Context, log: (String) -> Unit): Boolean {
        ShizukuHelper.exec("mkdir -p $WORK_DIR && chmod 777 $WORK_DIR")
        // 一次命令取回全部大小：每多一次 exec 就多一次 Shizuku 远程流可能返回空的概率
        val sizes = statSizes()
        for (name in ASSETS) {
            val target = "$WORK_DIR/$name"
            val bytes = try {
                ctx.assets.open(name).use { it.readBytes() }
            } catch (e: Throwable) {
                log("  读取 asset $name 失败: ${e.message}")
                return false
            }
            val want = bytes.size.toLong()
            // 只看文件大小：之前遇到过 writeViaStdin 写出一份等长的全 NUL 文件，
            // 光靠 test -f 会把它当成已就绪，导致后面脚本一句输出都没有。
            val onDevice = sizes[name] ?: -1L
            if (onDevice == want) {
                log("  $name 已就绪 ($want bytes)")
                continue
            }
            log("  写入 $name ($want bytes, 设备上是 ${if (onDevice < 0) "无" else "$onDevice bytes"}) ...")
            var ok = false
            for (attempt in 1..3) {
                if (writeViaStdin(target, bytes)) {
                    val got = sizeOf(target)
                    if (got == want) {
                        ok = true
                        break
                    }
                    log("    第 $attempt 次大小不符 ($got / $want)，重试")
                } else {
                    log("    第 $attempt 次写入失败，重试")
                }
            }
            if (!ok) {
                log("  写入 $name 失败（已重试 3 次）")
                return false
            }
            log("    $name OK")
        }
        ShizukuHelper.exec(
            "chmod 755 $WORK_DIR/pk64560 $WORK_DIR/ptyrun " +
                "$WORK_DIR/kp_arm.sh $WORK_DIR/ksu_load_setup.sh"
        )
        return ensureKsud(log)
    }

    /**
     * ksud 不随包分发：它的许可与 KernelSU 一致（GPL-3.0），而且 5 MB 会占掉
     * 整个 APK 一半以上体积。直接从用户已装的 KernelSU 管理器 APK 里抽——
     * 这样版本永远等于用户手上那个管理器，不会出现内核/用户空间 uapi 不匹配。
     */
    private fun ensureKsud(log: (String) -> Unit): Boolean {
        val existing = sizeOf("$WORK_DIR/ksud")
        if (existing >= KSUD_MIN_BYTES) {
            log("  ksud 已就绪 ($existing bytes)")
            return true
        }

        val apk = ShizukuHelper.exec(
            "pm path $KSU_PKG 2>/dev/null | head -1 | sed 's/^package://'"
        ).trim()
        if (apk.isEmpty() || !apk.endsWith(".apk")) {
            log("  未找到 KernelSU 管理器（$KSU_PKG），请先安装它再试")
            return false
        }
        val baseDir = apk.removeSuffix("/base.apk")
        log("  从 $KSU_PKG 提取 ksud ...")
        val out = ShizukuHelper.exec(
            "cp '$baseDir/lib/arm64/libksud.so' $WORK_DIR/ksud && " +
                "chmod 755 $WORK_DIR/ksud && stat -c %s $WORK_DIR/ksud", 90
        ).trim()
        val size = out.lines().lastOrNull()?.trim()?.toLongOrNull() ?: -1L
        if (size < KSUD_MIN_BYTES) {
            log("  提取失败（$out）；请确认 KernelSU 管理器已安装且为 arm64 版本")
            return false
        }
        log("  ksud 提取成功 ($size bytes)")
        return true
    }

    /** 一条命令取回全部载荷的字节数；缺失的记 -1 */
    private fun statSizes(): Map<String, Long> {
        val cmd = ASSETS.joinToString("; ") { n ->
            "printf '$n '; stat -c %s $WORK_DIR/$n 2>/dev/null || echo -1"
        }
        val out = ShizukuHelper.exec(cmd)
        val map = HashMap<String, Long>()
        for (line in out.lines()) {
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size >= 2 && parts[0] in ASSETS) {
                map[parts[0]] = parts[1].toLongOrNull() ?: -1L
            }
        }
        return map
    }

    /** 单独查一个文件的字节数；不存在返回 -1 */
    private fun sizeOf(path: String): Long =
        ShizukuHelper.exec("stat -c %s '$path' 2>/dev/null || echo -1")
            .trim().lines().firstOrNull()?.trim()?.toLongOrNull() ?: -1L

    /**
     * App 无权直接写 /data/local/tmp，用 `cat > path` 通过 Shizuku 的 stdin 灌字节流。
     *
     * 判据只看文件大小，不看 waitFor。ShizukuRemoteProcess.waitFor(timeout, unit) 实测会
     * 返回 false——哪怕进程已经正常退出、文件也已完整落盘（918 字节的脚本被它连判 3 次
     * "写入失败"，而设备上那份文件的大小和时间戳都是对的）。同一个类的 exitValue() 之前
     * 也抛过 IllegalThreadStateException，这个封装对"进程是否结束"的反馈整体不可信。
     */
    private fun writeViaStdin(path: String, bytes: ByteArray): Boolean = try {
        val p = ShizukuHelper.newProcess(arrayOf("sh", "-c", "cat > '$path'"))
        p.outputStream.use {
            it.write(bytes)
            it.flush()
        }
        val want = bytes.size.toLong()
        var ok = false
        for (round in 1..3) {
            Thread.sleep(700)
            if (sizeOf(path) == want) {
                ok = true
                break
            }
        }
        ok
    } catch (e: Throwable) {
        false
    }

    /**
     * ptyrun 会 fork 一个 pty 再运行 exploit —— 这是关键：
     * exploit 最后 execl("/system/bin/sh", "sh", "-i")，没有 tty 时 sh 会立刻退出，
     * 命令就被外层 shell 抢走，root shell 白起。
     */
    private fun runExploit(log: (String) -> Unit): Boolean {
        val shellCmd = "cd $WORK_DIR && ./ptyrun ./pk64560 --run 2>&1"
        val p = try {
            ShizukuHelper.newProcess(arrayOf("sh", "-c", shellCmd))
        } catch (e: Throwable) {
            log("  启动 exploit 失败: ${e.message}")
            return false
        }

        val reader = p.inputStream.bufferedReader()
        val writer = p.outputStream
        var injected = false
        val ansi = Regex("\u001b\\[[0-9;]*m")
        val deadline = System.currentTimeMillis() + EXPLOIT_BUDGET_MS

        try {
            while (System.currentTimeMillis() < deadline) {
                val raw = reader.readLine() ?: break
                val line = raw.replace("\r", "").replace(ansi, "")
                if (line.isBlank()) continue
                log(line)

                if (!injected && line.contains("ROOT_CHILD_HOLD_PASS")) {
                    injected = true
                    Thread.sleep(1000)
                    writer.write("sh $WORK_DIR/ksu_load_setup.sh\n".toByteArray())
                    writer.flush()
                    Thread.sleep(300)
                    writer.write("exit\n".toByteArray())
                    writer.flush()
                    log(">>> 已向 root shell 注入 ksud late-load 命令")
                }
                if (line.contains("KSULOAD_END")) break
            }
            p.waitFor(15, TimeUnit.SECONDS)
        } catch (e: Throwable) {
            log("  读取 exploit 输出中断: ${e.message}")
        }

        if (!injected) log("  未捕获到 ROOT_CHILD_HOLD_PASS（exploit 可能未进入 root shell）")
        return true
    }
}
