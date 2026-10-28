package com.ksuroot.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.os.Bundle
import android.text.Html
import android.text.method.LinkMovementMethod
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import java.util.Locale
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var scrollLog: ScrollView
    private lateinit var btnEnable: Button
    private lateinit var btnCheck: Button
    private lateinit var btnDisable: Button
    private lateinit var btnExport: Button
    private lateinit var btnCopy: Button
    private lateinit var tvAbout: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvProgress: TextView

    private val logBuf = StringBuilder()
    private var running = false

    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == ShizukuHelper.PERM_REQUEST) {
                appendLog(
                    if (grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED)
                        "Shizuku 已授权，可以开始" else "Shizuku 授权被拒绝"
                )
                refresh()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvLog = findViewById(R.id.tvLog)
        scrollLog = findViewById(R.id.scrollLog)
        btnEnable = findViewById(R.id.btnEnable)
        btnCheck = findViewById(R.id.btnCheck)
        btnDisable = findViewById(R.id.btnDisable)
        btnExport = findViewById(R.id.btnExport)
        btnCopy = findViewById(R.id.btnCopy)
        tvAbout = findViewById(R.id.tvAbout)
        progressBar = findViewById(R.id.progressBar)
        tvProgress = findViewById(R.id.tvProgress)

        btnCheck.setOnClickListener { refresh() }
        btnEnable.setOnClickListener { startEnable() }
        btnDisable.setOnClickListener { startDisable() }
        btnExport.setOnClickListener { doExport() }
        btnCopy.setOnClickListener { doCopy() }
        tvAbout.setOnClickListener { showAbout() }

        try {
            Shizuku.addRequestPermissionResultListener(permissionListener)
        } catch (e: Throwable) {
            appendLog("Shizuku 监听注册失败: ${e.message}")
        }

        refresh()
    }

    override fun onResume() {
        super.onResume()
        checkPending()
    }

    override fun onDestroy() {
        try {
            Shizuku.removeRequestPermissionResultListener(permissionListener)
        } catch (_: Throwable) {
        }
        super.onDestroy()
    }

    /** 断点续跑：上次跑到一半设备重启 / 进程被杀，这里把按钮换成「继续上次」 */
    /** onResume 会反复触发，用它避免同一次中断被提示多遍 */
    private var pendingNotified = false

    private fun checkPending() {
        if (running) return
        val p = RootController.pending(this)
        if (p == RootController.Pending.NONE) {
            pendingNotified = false
            btnEnable.text = getString(R.string.btn_enable)
            return
        }
        // 按钮文案每次都刷新（用户可能刚从别的页面回来），提示只打一次
        if (pendingNotified) return
        pendingNotified = true
        when (p) {
            RootController.Pending.NONE -> Unit
            RootController.Pending.REBOOTED -> {
                btnEnable.text = getString(R.string.btn_continue)
                appendLog("---")
                appendLog("检测到上次运行被设备重启打断，停在「${RootController.lastStep(this)}」。")
                appendLog("请先确认 Shizuku 已启动（重启后它不会自己回来），再点「继续上次」。")
            }
            RootController.Pending.KILLED -> {
                btnEnable.text = getString(R.string.btn_continue)
                appendLog("---")
                appendLog("检测到上次运行被系统杀掉（设备未重启），停在「${RootController.lastStep(this)}」。")
                appendLog("可点「继续上次」重跑。")
            }
        }
    }

    private fun appendLog(s: String) {
        runOnUiThread {
            logBuf.append(s).append('\n')
            tvLog.text = logBuf.toString()
            scrollLog.post { scrollLog.fullScroll(ScrollView.FOCUS_DOWN) }
            updateProgress(s)
        }
    }

    /**
     * 从日志里推阶段进度。exploit 那段要跑几分钟，光看「[4/5]」不动会让人以为卡死，
     * 所以额外解析 attempt 计数，让进度条在那几分钟里往前走。
     */
    private fun updateProgress(line: String) {
        // 只有真正在跑的时候才推进度。断点续跑的提示日志里也带「[4/5]」，
        // 不挡住的话空闲状态会莫名其妙显示 60%。
        if (!running) return
        val pct = when {
            line.contains("=== 成功") || line.contains("=== 失败") -> 100
            line.contains("[1/5]") -> 8
            line.contains("[2/5]") -> 25
            line.contains("[3/5]") -> 45
            line.contains("[5/5]") -> 92
            line.contains("[4/5]") -> 60
            else -> {
                val m = ATTEMPT.find(line) ?: return
                val n = m.groupValues[1].toIntOrNull() ?: return
                // 60 → 90 之间按 attempt 数推进；attempts 上限 128，前 30 次占满这段
                (60 + n.coerceAtMost(30) * 1.0).toInt().coerceAtMost(90)
            }
        }
        progressBar.progress = pct
        tvProgress.text = when {
            pct >= 100 -> ""
            pct > 0 -> "$pct%"
            else -> ""
        }
    }

    private fun refresh() {
        lifecycleScope.launch {
            tvStatus.text = "检查中 ..."
            val st = RootController.checkStatus(this@MainActivity)
            val uptime = RootController.uptimeSec()
            val load1 = RootController.load1m()
            tvStatus.text = buildString {
                append("Shizuku: ").append(st.shizuku).append('\n')
                append("root: ").append(if (st.root) "已开启 ✅" else "未开启").append('\n')
                append("SELinux: ").append(st.selinux).append('\n')
                append("模块: ").append(if (st.module.contains("kernelsu")) "kernelsu 已加载" else "未加载").append('\n')
                append("系统: ").append(String.format(Locale.US, "负载 %.1f", load1))
                if (!st.root) {
                    // 刚开机和负载高是两件事，提示要分开说，
                    // 否则会出现「负载 0.0 · 偏高」这种自相矛盾的显示
                    if (uptime < 300) {
                        append(" · 刚开机 ").append(uptime).append("s，建议等 5 分钟")
                    } else if (load1 > 8f) {
                        append(" · 偏高，建议等系统闲下来再开")
                    }
                }
            }
        }
    }

    // ---------------- 高风险操作二次确认 ----------------

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()

    private fun riskDialog(
        title: String,
        html: String,
        confirmText: String,
        showCancel: Boolean = true,
        cancelText: String = "取消",
        onConfirm: () -> Unit
    ) {
        val tv = TextView(this).apply {
            text = Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY)
            textSize = 13.5f
            setLineSpacing(dp(3f).toFloat(), 1f)
            setPadding(dp(20f), dp(12f), dp(20f), dp(4f))
            movementMethod = LinkMovementMethod.getInstance()
            setLinkTextColor(0xFF3DDC84.toInt())
        }
        val scroll = ScrollView(this).apply { addView(tv) }

        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton(confirmText) { _, _ -> onConfirm() }
        if (showCancel) builder.setNegativeButton("取消", null)
        val dlg = builder.create()

        dlg.show()
        // 弹窗背景用主界面同一套表面色；Material3 默认会给 surface 叠 primary tint，这里直接画
        val d = resources.displayMetrics.density
        val bg = GradientDrawable().apply {
            setColor(0xFF1E1E21.toInt())
            cornerRadius = 24f * d
            setStroke((1f * d).toInt(), 0xFF2A2A2E.toInt())
        }
        val inset = (24f * d).toInt()
        dlg.window?.setBackgroundDrawable(InsetDrawable(bg, inset, inset, inset, inset))
        dlg.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.92f).toInt(),
            (resources.displayMetrics.heightPixels * 0.78f).toInt()
        )
    }

    private fun startEnable() {
        if (running) return
        if (!ShizukuHelper.isBinderAlive()) {
            appendLog("Shizuku 未运行。请先打开 Shizuku App，点「启动服务」。")
            return
        }
        if (!ShizukuHelper.hasPermission()) {
            ShizukuHelper.requestPermission()
            appendLog("已请求 Shizuku 授权，请在弹窗里点「允许」，然后再点一次按钮")
            return
        }
        // 二次确认：这里才是真正会改内核、有崩溃风险的地方
        riskDialog(
            "⚠ 开启临时 root",
            ENABLE_RISK_HTML,
            "我已知晓风险，继续"
        ) { doEnable() }
    }

    private fun doEnable() {
        if (running) return
        running = true
        btnEnable.isEnabled = false
        btnCheck.isEnabled = false
        btnExport.isEnabled = false
        btnCopy.isEnabled = false
        logBuf.setLength(0)
        progressBar.visibility = View.VISIBLE
        progressBar.progress = 0
        tvProgress.text = ""
        appendLog("=== 开始 ===")
        lifecycleScope.launch {
            val ok = try {
                RootController.enableRoot(this@MainActivity) { line -> appendLog(line) }
            } catch (e: Throwable) {
                appendLog("异常: ${e.message}")
                false
            }
            appendLog(if (ok) "=== 成功：su 已可用 ===" else "=== 失败（看上面日志）===")
            btnEnable.isEnabled = true
            btnCheck.isEnabled = true
            btnExport.isEnabled = true
            btnCopy.isEnabled = true
            running = false
            btnEnable.text = getString(R.string.btn_enable)
            progressBar.postDelayed({
                progressBar.visibility = View.GONE
                tvProgress.text = ""
            }, 1500)
            refresh()
        }
    }

    private fun startDisable() {
        if (running) return
        riskDialog(
            "⚠ 关闭 root 并重启",
            DISABLE_RISK_HTML,
            "立即重启"
        ) {
            appendLog("=== 关闭 root：重启设备 ===")
            lifecycleScope.launch {
                RootController.disableRoot(this@MainActivity) { line -> appendLog(line) }
            }
        }
    }

    private fun doExport() {
        if (logBuf.length == 0) {
            appendLog("日志还是空的，先跑一次再导出")
            return
        }
        val path = RootController.exportLog(this, logBuf.toString())
        if (path != null) {
            appendLog("日志已导出到 $path")
        } else {
            appendLog("导出失败（没有存储权限或被系统拦截）")
        }
    }

    private fun doCopy() {
        if (logBuf.length == 0) {
            appendLog("日志还是空的")
            return
        }
        val text = logBuf.toString()
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("ksu-root-log", text))
        appendLog("日志已复制到剪贴板（${text.length} 字符）")
    }

    private fun showAbout() {
        riskDialog(
            getString(R.string.about_title),
            ABOUT_HTML,
            getString(R.string.btn_ok),
            showCancel = false
        ) { }
    }

    companion object {
        /** exploit 内部的尝试计数，用来让进度条在 [4/5] 那几分钟里往前走 */
        private val ATTEMPT = Regex("ATTEMPT_BEGIN attempt=(\\d+)")

        private const val ENABLE_RISK_HTML = """
<b>会做什么</b><br>
运行 CVE-2026-64560 内核提权链，把 KernelSU 模块加载进内核。全程约 3–8 分钟，中途不能退出 App。<br><br>

<b>可能出现的现象</b><br>
· 屏幕卡住、日志长时间不动 —— 正常，正在跑内核竞争<br>
· 手机突然黑屏并自动重启 —— 内核 panic，实测约 40% 概率<br>
· 重启后 root 不存在，需要重新来过<br><br>

<b>可能留下的后果</b><br>
· root 生效后，内核里会留一颗「延迟炸弹」：之后某个 App 调用定时器相关系统调用时可能突然崩溃重启。重启一次即清除<br>
· panic 重启后 SELinux 可能短暂变成 Permissive，此时再跑会失败，需要再正常重启一次<br>
· root 只在本次开机有效，重启即失效<br>
· 提权链会改写内核随机数表，<b>/proc/sys/kernel/random/boot_id 会变成假值</b>，依赖它的功能可能异常<br><br>

<b>免责声明</b><br>
本功能基于公开的漏洞利用代码，属内核级操作，具有不可完全预测的风险。请自行承担：数据丢失、应用崩溃、系统不稳定、设备损坏、保修失效，以及使用 root 造成的任何直接或间接损失。作者不对上述后果负责。<br>
<b>建议先备份重要数据再执行。</b>
        """

        private const val DISABLE_RISK_HTML = """
<b>会做什么</b><br>
立即重启手机。重启后 root 权限消失，Shizuku 服务也会停止。<br><br>

<b>可能出现的现象</b><br>
· 画面黑掉，约 40–60 秒后回到锁屏<br>
· 正在运行的任务被打断<br><br>

<b>可能留下的后果</b><br>
· 所有未保存的数据丢失<br>
· 下次要用 root，必须重新启动 Shizuku 并重跑完整流程<br><br>

<b>免责声明</b><br>
重启不可撤销。请先保存手头的工作。作者不对因重启造成的数据丢失负责。
        """

        private const val ABOUT_HTML = """
<b>KSU Root v${BuildConfig.VERSION_NAME}</b><br>
临时 root 工具，重启即失效。<br><br>

<b>项目地址</b><br>
<a href="https://github.com/wanwuzhishi/ksu-root">github.com/wanwuzhishi/ksu-root</a><br><br>

<b>适配设备（仅此一款）</b><br>
REDMI K90 Max（型号 2604FRK1EC，代号 prague）<br>
系统 OS4.0.0.14.XAACNXM（Android 17）<br>
内核 6.12.69-android16-6-g586bfab1b9c5-abogki536749445-4k<br>
安全补丁 2026-09-01<br><br>

<b>已测试通过的组合</b><br>
REDMI K90 Max / OS4.0.0.14.XAACNXM / 6.12.69-android16-6-g586bfab1b9c5-abogki536749445-4k<br>
2026-10-25 两次成功：<br>
· 冷启动一次过，约 5 分钟，全程无重启<br>
· 中途 panic 重启，靠「断点续跑」第二次成功<br><br>

<b>其他机型或系统版本：不支持，请勿尝试。</b><br>
本工具用的内核地址、结构体偏移全部是在上面这一版内核上实测出来的。换一个系统版本这些值就失效，写下去等于直接改坏内核内存，结果是当场 panic 或随机重启。<br><br>

<b>项目限制</b><br>
· 临时 root：重启即失效，不写 boot 分区，不做持久化<br>
· 前提：Shizuku（或 Shevery 等兼容实现）已在运行 + KernelSU 管理器已安装<br>
· 约 40% 概率触发内核 panic 并重启（实测 17 次里 7 次）<br>· 系统负载高时失败率更高。状态行会显示当前负载，<b>「偏高」时建议先等一等</b><br>
· 成功后内核里会留一颗 dangling timer，之后可能随机崩溃，重启清除<br>
· 不提供 Magisk 模块、Zygisk、隐藏、开机自启<br>
· 仅在 prague 这一款设备上验证过<br><br>

<b>支持的 Shizuku 实现</b><br>
官方 Shizuku 与 Shevery <b>已实测可用</b>。Shizuku-Next、yangFenTuoZi/Shizuku、ShizukuPlus 的 dropin 版与其同包名同权限名，理论可用。Nightzuku 和 ShizukuPlus 独立版把权限名改成了自己的前缀，官方 API 库请求不到，暂不兼容。<br><br>

<b>免责声明</b><br>
本软件仅供安全研究与自有设备调试使用。使用即表示你已知悉并接受：<br>
· 设备可能 panic、重启、丢失未保存数据<br>
· 系统稳定性与续航可能受影响<br>
· 设备保修可能失效<br>
· 需自行确认在当地法律法规下合法<br><br>
<b>请勿用于非自有设备。作者不对任何直接或间接损失负责。</b><br><br>

<b>致谢</b><br>
利用代码 <a href="https://github.com/ChaYeWuu/prague-CVE-2026-64560-OS4.0">ChaYeWuu/prague-CVE-2026-64560-OS4.0</a>（Apache-2.0）<br>
KernelSU <a href="https://github.com/tiann/KernelSU">tiann/KernelSU</a>（GPL-3.0，运行期从已装管理器提取）<br>
Shizuku <a href="https://github.com/RikkaApps/Shizuku">RikkaApps/Shizuku</a>（Apache-2.0）<br>
漏洞 <a href="https://nvd.nist.gov/vuln/detail/CVE-2026-64560">CVE-2026-64560</a>（posix-cpu-timers UAF）
        """
    }
}
