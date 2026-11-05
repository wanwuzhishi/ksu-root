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
    private const val KSUD_MIN_BYTES = 4_000_000L

    /**
     * ksud 候选表，按默认优先级排列。SukiSU 在前，KernelSU 兜底。
     *
     * 两者同源分支，CLI 完全兼容 —— 实测 `late-load --allow-shell`、
     * `feature get/set/list/check/load/save`、`debug info` 参数名与输出格式一致，
     * 所以同一套脚本不用改。但**各自的内核模块不同**：换 ksud 等于换 ko，
     * 因为 late-load 加载的就是 ksud 自带的那一份。
     *
     * ksudLib 是管理器 APK 里那个改名伪装的 native 可执行文件（带
     * ELF INTERP=/system/bin/linker64），两家都没叫 ksud。
     */
    data class KsuManager(val pkg: String, val ksudLib: String, val label: String)

    val KSU_MANAGERS = listOf(
        KsuManager("com.sukisu.ultra", "libfdbqiupsrx.so", "SukiSU Ultra"),
        KsuManager("me.weishu.kernelsu", "libksud.so", "KernelSU"),
    )

    /**
     * 一个管理器的探测结果。ksudSize < 0 表示 APK 里那个 lib 读不到。
     *
     * [probed] 区分「确实没装」和「没法查」：Shizuku 没授权时 `pm path` 返回空，
     * 只看 apk.isEmpty() 会把它当成未安装，UI 上就会莫名其妙地说「都没装」——
     * 用户明明装着两个管理器。线上真踩到过。
     */
    data class ManagerInfo(
        val manager: KsuManager,
        val apk: String,
        val ksudSize: Long,
        val version: String,
        val probed: Boolean,
    ) {
        val installed: Boolean get() = probed && apk.isNotEmpty()
        val usable: Boolean get() = installed && ksudSize >= KSUD_MIN_BYTES
    }
    private const val EXPLOIT_BUDGET_MS = 8 * 60_000L
    // KernelSU 在 late-load 之后才 install 出 /system/bin/su，实测要两三分钟。
    // 只查一次会把成功判成失败（线上真踩到过，还顺手标了脏导致用户被迫重启）。
    private const val SU_VERIFY_TIMEOUT_MS = 4 * 60_000L
    private const val SU_VERIFY_INTERVAL_MS = 5_000L

    /**
     * exploit 跑完后的三种结局。区分它们是「该不该标脏」的唯一可靠依据 ——
     * 光看进程退出码不够：exploit 在 stage0 之后任何一步失败都会走
     * bridge_cleanup 把内核复原，然后以非零 status 退出，
     * 退出码一样但内核状态天差地别。
     */
    private enum class ExploitOutcome {
        /** 在改写内核之前就拒绝了（指纹/UHID/环境 gate），内核一个字节没动 */
        GATE_REFUSED,

        /** 动过内核，但 exploit 自己复原了（日志里 KERNEL_STATE_RESTORE_PASS dirty=0） */
        CLEAN_RESTORE,

        /** 动过内核且没拿到干净复原的确认 —— 状态不可知，按脏处理 */
        TOUCHED,
    }

    /** runExploit 的双轴结果：内核是否干净 + 有没有真的进到 root shell（决定要不要等 su）。 */
    private data class ExploitResult(
        val outcome: ExploitOutcome,
        val reachedRootShell: Boolean,
    )

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

    // ---------------------------------------------------------------- 系统状态

    /** 开机到现在的秒数，用于判断系统是否刚起来 */
    fun uptimeSec(): Int =
        ShizukuHelper.exec("cut -d. -f1 /proc/uptime").trim().toIntOrNull() ?: 0

    /** 1 分钟平均负载。stage0 靠线程竞争拿窗口，负载越高越容易打偏。 */
    fun load1m(): Float =
        ShizukuHelper.exec("cut -d' ' -f1 /proc/loadavg").trim().toFloatOrNull() ?: 0f

    // ---------------------------------------------------------------- 断点续跑

    private const val PREF = "ksu_root_state"
    private const val KEY_RUNNING = "running"
    private const val KEY_ELAPSED = "elapsed"
    private const val KEY_STEP = "step"
    private const val KEY_TIME = "time"

    /** NONE=没有未完成任务，REBOOTED=上次被整机重启打断，KILLED=上次进程被杀但设备没重启 */
    enum class Pending { NONE, REBOOTED, KILLED }

    // 失败的运行会在内核里留下脏东西：比如 PIPE_PREP_FAIL 之后，restore_misc_list 只把
    // misc_list 的头指回去了，中间那个伪造的 miscdevice 节点没摘干净，它指向已释放的内存。
    // 不重启就再跑，下一次 misc_open 遍历链表必然踩到 —— 实测 pstore 里就是
    // misc_open+0x4c 崩的，x23 正是上一轮的 misc_list head。
    private const val KEY_DIRTY = "dirty"
    private const val KEY_DIRTY_BOOT = "dirty_boot"

    /**
     * 本次开机的「墙上时间起点」= 当前时刻 - 开机时长。重启后这值会变（晚至少几秒）。
     *
     * 为什么不用 elapsedRealtime 记：它还原本能判断「重启过没」（变小就是重启过），
     * 但拿来判断「没重启过」会误判——失败时开机 171 秒、重启后隔 5 分钟才开 App，
     * 这时的 elapsedRealtime 比记录值大，就被当成「没重启」，于是重启完还是被拦住。
     * 线上真踩到过这个。
     */
    private fun bootWallTime(): Long = System.currentTimeMillis() - SystemClock.elapsedRealtime()

    /** 记录「这次跑完内核可能不干净」。成功一次会清掉。 */
    fun markDirty(ctx: Context) {
        prefs(ctx).edit()
            .putBoolean(KEY_DIRTY, true)
            .putLong(KEY_DIRTY_BOOT, bootWallTime())
            .apply()
    }

    fun clearDirty(ctx: Context) {
        prefs(ctx).edit().putBoolean(KEY_DIRTY, false).apply()
    }

    /**
     * true = 上次失败后还没重启过，此时再跑会在 misc_open 阶段 panic。
     * 判据：本次开机的墙上时间和记录值在同一个 boot 内几乎相同（只差毫秒抖动），
     * 重启后 bootWallTime 会往后跳，差值远超容差，就认为内核已经干净了。
     */
    fun blockedByDirtyState(ctx: Context): Boolean {
        val p = prefs(ctx)
        if (!p.getBoolean(KEY_DIRTY, false)) return false
        val dirtyBootWall = p.getLong(KEY_DIRTY_BOOT, 0L)
        if (dirtyBootWall <= 0L) return false
        // 老版本存的是 elapsedRealtime（开机毫秒数，通常 < 1e7），
        // 和新格式（epoch 秒级时间戳，约 1e12 量级）差好几个数量级，直接忽略
        if (dirtyBootWall < 1_000_000_000_000L) return false
        return kotlin.math.abs(bootWallTime() - dirtyBootWall) < 10_000L
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- 管理器选择

    /**
     * 用户手动选定的管理器包名。空 = 自动（按 [KSU_MANAGERS] 顺序取第一个能用的）。
     *
     * 为什么要能手动选：两个管理器常同时装着（换分支但没卸旧的，很常见）。
     * 自动模式按表序取 SukiSU，看起来总是对的那一个；但用户可能就是想用
     * KernelSU，或者装了两个 SukiSU 系的分支包，这时必须能指定。
     */
    private const val KEY_MANAGER = "manager_pkg"

    fun selectedManagerPkg(ctx: Context): String =
        prefs(ctx).getString(KEY_MANAGER, "") ?: ""

    fun setSelectedManagerPkg(ctx: Context, pkg: String) {
        prefs(ctx).edit().putString(KEY_MANAGER, pkg).apply()
    }

    /**
     * 把用户选择排到最前面，其余保持原顺序。**只影响顺序，不影响可用性判断** ——
     * 选中的那个要是取不到 ksud（没装/被拆包），会自然落到后面的候选上，
     * 而不是直接失败，这样用户选错了也不至于卡死。
     */
    private fun orderedCandidates(ctx: Context): List<KsuManager> {
        val sel = selectedManagerPkg(ctx)
        if (sel.isEmpty()) return KSU_MANAGERS
        val chosen = KSU_MANAGERS.filter { it.pkg == sel }
        return chosen + KSU_MANAGERS.filter { it.pkg != sel }
    }

    /** 探测所有候选管理器：装了没、APK 里 ksud 多大、版本号多少。 */
    suspend fun probeManagers(ctx: Context): List<ManagerInfo> = withContext(Dispatchers.IO) {
        val out = ArrayList<ManagerInfo>()
        // 查不了就别装作查过了：Shizuku 未就绪时所有 exec 都返回空字符串
        val canQuery = ShizukuHelper.isBinderAlive() && ShizukuHelper.hasPermission()
        if (!canQuery) {
            for (m in KSU_MANAGERS) out.add(ManagerInfo(m, "", -1L, "", probed = false))
            return@withContext out
        }
        for (m in KSU_MANAGERS) {
            val apk = ShizukuHelper.exec(
                "pm path ${m.pkg} 2>/dev/null | head -1 | sed 's/^package://'"
            ).trim()
            if (apk.isEmpty() || !apk.endsWith(".apk")) {
                out.add(ManagerInfo(m, "", -1L, "", probed = true))
                continue
            }
            val baseDir = apk.removeSuffix("/base.apk")
            val ksudSize = sizeOf("$baseDir/lib/arm64/${m.ksudLib}")
            // versionName 用 dumpsys 取，pm 在这代系统上不一定给
            val ver = ShizukuHelper.exec(
                "dumpsys package ${m.pkg} 2>/dev/null | grep -m1 versionName | tr -d ' '"
            ).trim().removePrefix("versionName=")
            out.add(ManagerInfo(m, apk, ksudSize, ver, probed = true))
        }
        out
    }

    /**
     * 内核里是否已经有 kernelsu 模块。换了管理器分支后必须重启：
     * 两家的模块名都叫 `kernelsu`，老的还在内核里，新的 insmod 会
     * `File exists (os error 17)`（实测过，vermagic 检查是过的）。
     */
    fun moduleLoaded(ctx: Context): Boolean =
        ShizukuHelper.exec("grep -ci kernelsu /proc/modules").trim().toIntOrNull()?.let { it > 0 } == true

    /** 当前实际会用的管理器（用户选定优先，取不到就顺延），没有可用项返回 null。 */
    fun effectiveManager(infos: List<ManagerInfo>, ctx: Context): ManagerInfo? {
        val order = orderedCandidates(ctx).map { it.pkg }
        return infos.filter { it.usable }.minByOrNull { order.indexOf(it.manager.pkg) }
    }

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
        if (blockedByDirtyState(ctx)) {
            log("上次运行失败后没有重启过。")
            log("内核里还留着脏节点（伪造的 miscdevice 在 misc_list 里没摘干净），")
            log("不重启就再跑，会在 misc_open 阶段 panic。请重启手机后再试。")
            return@withContext false
        }
        markRunning(ctx, "启动")
        var ok = false
        // 默认按「在跑 exploit 之前就失败了」算 —— Shizuku 没授权、载荷写不进去、
        // kprobe 布防失败这些都在 runExploit 之前 return，内核一个字节没动。
        var outcome = ExploitOutcome.GATE_REFUSED
        try {
            ok = runSteps(ctx, log, onOutcome = { outcome = it })
            // 只有 TOUCHED 才需要人工重启。GATE_REFUSED / CLEAN_RESTORE 都说明
            // 内核现在是干净的，拦着用户重启纯属折腾。
            when (outcome) {
                ExploitOutcome.GATE_REFUSED,
                ExploitOutcome.CLEAN_RESTORE -> clearDirty(ctx)
                ExploitOutcome.TOUCHED -> if (ok) clearDirty(ctx) else markDirty(ctx)
            }
            ok
        } catch (e: Throwable) {
            if (outcome == ExploitOutcome.TOUCHED) markDirty(ctx)
            throw e
        } finally {
            // 正常返回（成功/失败）都清掉；panic 重启时进程被杀，这里不会执行，标记就留下来了
            clearRunning(ctx)
        }
    }

    private fun runSteps(
        ctx: Context,
        log: (String) -> Unit,
        onOutcome: (ExploitOutcome) -> Unit,
    ): Boolean {
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
        val res = runExploit(log)
        onOutcome(res.outcome)
        // 没进 root shell 就一定不会加载 LKM，su 不可能凭空出现。
        // 之前不判这个，PIPE_CACHE_GATE 失败后白轮询满 4 分钟。
        if (!res.reachedRootShell) {
            log("  exploit 没有进入 root shell，跳过 su 验证")
            return false
        }

        step("[5/5] 验证 su ...")
        // su 出现在 /system/bin/su 之后仍可能调不通：KernelSU 的 shell_su_adb_only
        // （feature ID=6）默认开启，只放行 adbd / ksu 域派生的进程。Shizuku 的
        // shizuku_server 是 setsid 起的、父进程被 init 接管，不在放行范围，表现就是
        // 「su: inaccessible or not found」（连 exec 都被拦），而 adb shell 直连一直正常。
        // 注入脚本里已经 ksud feature set shell_su_adb_only 0，这里再加一层兜底：
        // 如果真的调不通，就再关一次 feature 然后重试，别把成功判成失败。
        var id = ""
        var waitedMs = 0L
        var retriedFeature = false
        while (waitedMs <= SU_VERIFY_TIMEOUT_MS) {
            id = ShizukuHelper.exec("su -c id 2>&1")
            if (id.contains("uid=0")) break
            if (waitedMs == 0L) {
                log("  su 还没就绪，等待 KernelSU 完成 install（最多 4 分钟）...")
            }
            if (!retriedFeature && waitedMs >= 30_000L) {
                retriedFeature = true
                // 不能用 ls 的字符串判断：文件不存在时 ls 的输出里也含这个路径
                // （"ls: /system/bin/su: No such file or directory"），contains 必然为真，
                // 于是每次失败都白跑一次 feature set。
                val legacy = ShizukuHelper.exec(
                    "[ -e /system/bin/su ] && echo SU_EXISTS || echo SU_MISSING",
                )
                if (legacy.contains("SU_EXISTS")) {
                    log("  su 已存在但调用被拒，关闭 shell_su_adb_only 后重试 ...")
                    ShizukuHelper.exec(
                        "$WORK_DIR/ksud feature set shell_su_adb_only 0 2>&1",
                        30,
                    )
                }
            }
            Thread.sleep(SU_VERIFY_INTERVAL_MS)
            waitedMs += SU_VERIFY_INTERVAL_MS
        }
        log(id)
        if (!id.contains("uid=0")) {
            // su 确实没起来。此时内核里 misc_list 已复原、pipe 页已归还，
            // 但 dangling timer 还在，下一次调 timer_delete 可能崩——按脏处理。
            log("  su 在 ${SU_VERIFY_TIMEOUT_MS / 1000} 秒内不可用，请重启后重试")
            return false
        }
        log("  su 就绪（等待 ${waitedMs / 1000} 秒）")
        return true
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
        return ensureKsud(ctx, log)
    }

    /**
     * ksud 不随包分发：它的许可与上游一致（GPL-3.0），而且 5~6 MB 会占掉
     * 整个 APK 一半以上体积。直接从用户已装的管理器 APK 里抽——
     * 这样版本永远等于用户手上那个管理器，不会出现内核/用户空间 uapi 不匹配。
     *
     * 顺序见 [orderedCandidates]：用户选定的排最前，其余按默认顺序兜底。
     * **必须按顺序命中而不是「谁在就先用谁」**：两个管理器可能同时装着
     * （很容易发生，用户换了分支但没卸载旧的），而我们已经从胜出者的 ko
     * 起了内核，混用会让 uapi 对不上。
     */
    private fun ensureKsud(ctx: Context, log: (String) -> Unit): Boolean {
        val paths = ArrayList<Pair<KsuManager, String>>()
        for (m in orderedCandidates(ctx)) {
            val apk = ShizukuHelper.exec(
                "pm path ${m.pkg} 2>/dev/null | head -1 | sed 's/^package://'"
            ).trim()
            if (apk.isEmpty() || !apk.endsWith(".apk")) continue
            paths.add(m to apk)
        }
        if (paths.isEmpty()) {
            log("  未找到 KernelSU / SukiSU 管理器，请先装其中一个再试")
            return false
        }

        var lastErr = ""
        for ((m, apk) in paths) {
            val baseDir = apk.removeSuffix("/base.apk")
            val src = "$baseDir/lib/arm64/${m.ksudLib}"
            val srcSize = sizeOf(src)
            if (srcSize < KSUD_MIN_BYTES) {
                lastErr = "读不到管理器内的 ${m.ksudLib}（$srcSize bytes @ $src）"
                continue
            }
            if (extractKsud(m, src, srcSize, log)) return true
            lastErr = "从 ${m.pkg} 提取 ksud 失败"
        }
        log("  $lastErr；请卸载重装管理器后再试")
        return false
    }

    /**
     * 覆盖式提取：**不做「已有够大就跳过」的短路**。
     *
     * 曾经为了省一次 cp 加过这个短路，结果用户换了管理器分支（KernelSU →
     * SukiSU）之后，旧的 5 MB ksud 满足「够大」，直接过关，于是 late-load
     * 加载的还是老分支的 ko，管理器一直报 uapi 不匹配。现在每次都重写，
     * 代价是一次 cp，换来「workdir 里的 ksud 永远等于当前选中的管理器」。
     */
    private fun extractKsud(
        m: KsuManager, src: String, srcSize: Long, log: (String) -> Unit,
    ): Boolean {
        log("  从 ${m.pkg} 提取 ksud ($srcSize bytes) ...")
        val out = ShizukuHelper.exec(
            "cp '$src' $WORK_DIR/ksud && " +
                "chmod 755 $WORK_DIR/ksud && stat -c %s $WORK_DIR/ksud", 90
        ).trim()
        val size = out.lines().lastOrNull()?.trim()?.toLongOrNull() ?: -1L
        if (size != srcSize) {
            log("  cp 失败（期望 $srcSize，实际 $out）")
            return false
        }
        // 大小对还不够。线上真见过大小完全正确、内容全是 NUL 的 ksud：
        // cp 静默失败时就这样，ksud 不报错也不干活，一路「成功」到最后 su 不存在。
        if (!ksudLooksValid(srcSize)) {
            log("  提取出的 ksud 内容异常（大小对但文件头不是 ELF），用流方式重写 ...")
            ShizukuHelper.exec(
                "cat '$src' > $WORK_DIR/ksud && chmod 755 $WORK_DIR/ksud", 120
            )
            if (!ksudLooksValid(srcSize)) return false
        }
        log("  ksud 提取成功（${m.pkg}，$size bytes）")
        return true
    }

    /** ksud 得能跑起来才算数：大小对 + 文件头是 ELF magic */
    private fun ksudLooksValid(wantSize: Long): Boolean {
        if (sizeOf("$WORK_DIR/ksud") != wantSize) return false
        val magic = ShizukuHelper.exec(
            "head -c 4 $WORK_DIR/ksud | od -An -tx1 | tr -d ' \\n'", 15
        ).trim()
        return magic == "7f454c46"
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
    private fun runExploit(log: (String) -> Unit): ExploitResult {
        val shellCmd = "cd $WORK_DIR && ./ptyrun ./pk64560 --run 2>&1"
        val p = try {
            ShizukuHelper.newProcess(arrayOf("sh", "-c", shellCmd))
        } catch (e: Throwable) {
            // 连进程都没起来，内核肯定没动过
            log("  启动 exploit 失败: ${e.message}")
            return ExploitResult(ExploitOutcome.GATE_REFUSED, false)
        }

        val reader = p.inputStream.bufferedReader()
        val writer = p.outputStream
        var injected = false
        val ansi = Regex("\u001b\\[[0-9;]*m")
        val deadline = System.currentTimeMillis() + EXPLOIT_BUDGET_MS
        var fatalGate = false
        // exploit 在 stage0 之后任何一步失败都会走 bridge_cleanup，最后打印
        // KERNEL_STATE_RESTORE_PASS dirty=0 —— 这是「内核已经复原」的唯一权威信号。
        // 有了它才能把这次失败判成「干净失败」，不逼用户白重启一次。
        var cleanRestore = false
        // bridge 建好之前的 gate（指纹 / UHID / 环境）根本不会碰内核。
        // 用 stage0 是否通过当分界线，比逐个匹配 gate 名字更可靠。
        var stage0Passed = false

        try {
            while (System.currentTimeMillis() < deadline) {
                val raw = reader.readLine() ?: break
                val line = raw.replace("\r", "").replace(ansi, "")
                if (line.isBlank()) continue
                log(line)
                if (line.startsWith("STAGE0_GATE_PASS")) stage0Passed = true
                if (line.startsWith("KERNEL_STATE_RESTORE_PASS")) cleanRestore = true
                if (line.startsWith("TARGET_PROFILE_GATE_FAIL") ||
                    line.startsWith("UHID_GATE_FAIL") ||
                    line.startsWith("ENV_GATE_FAIL") ||
                    line.startsWith("BOOTID_WRITE_MISS")
                ) fatalGate = true

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
        val outcome = when {
            // exploit 在真正动手之前就可能拒绝：指纹不匹配 exit(2)、UHID gate、
            // 环境 gate 等。这些情况下内核没被写过，不该算脏。
            fatalGate -> {
                log("  exploit 在改写内核之前就退出了")
                ExploitOutcome.GATE_REFUSED
            }
            // 没走到 root shell，但 exploit 自己复原并确认了：这次白跑，内核是干净的。
            // 判据是 cleanRestore 而不是 injected —— PIPE_CACHE_GATE 这类失败发生在
            // 建 bridge 之后、进 root shell 之前，injected 必然是 false。
            !injected && cleanRestore -> {
                log("  exploit 中途失败但已复原内核（KERNEL_STATE_RESTORE_PASS dirty=0）")
                ExploitOutcome.CLEAN_RESTORE
            }
            // 没复原确认 —— 状态不可知，按脏处理
            else -> ExploitOutcome.TOUCHED
        }
        return ExploitResult(outcome, injected)
    }
}
