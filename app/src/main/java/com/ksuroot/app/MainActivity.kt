package com.ksuroot.app

import androidx.appcompat.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Html
import android.text.method.LinkMovementMethod
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
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
    private lateinit var tvManager: TextView
    private lateinit var rowManager: View
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
        tvManager = findViewById(R.id.tvManager)
        rowManager = findViewById(R.id.rowManager)
        progressBar = findViewById(R.id.progressBar)
        tvProgress = findViewById(R.id.tvProgress)

        btnCheck.setOnClickListener { refresh() }
        btnEnable.setOnClickListener { startEnable() }
        btnDisable.setOnClickListener { startDisable() }
        btnExport.setOnClickListener { doExport() }
        btnCopy.setOnClickListener { doCopy() }
        tvAbout.setOnClickListener { showAbout() }
        rowManager.setOnClickListener { showManagerPicker() }

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
            // exploit 跑完后 App 还要轮询 su 最多 4 分钟，这段没有反馈会让人以为卡死
            if (s.startsWith("[5/5]")) showSuVerifyDialog()
            if (s.startsWith("=== ") || s.contains("su 就绪") || s.contains("不可用，请重启")) {
                dismissSuVerifyDialog()
            }
        }
        // 同步落盘 + fsync：exploit 跑飞时内核直接 panic，进程来不及做任何收尾，
        // 日志只在内存里等于没有。写到 App 私有目录，重启后还能 cat 出来。
        //
        // 必须显式 fsync —— File.appendText() 只 close()，数据进的是 page cache，
        // kernel panic 时 ext4 的 data=ordered 不保证它落盘。2026-11-06 连续三次
        // panic 后 run.log 里 4361/4590 字节是 NUL，最后一行永远看不到，等于白跑。
        try {
            FileOutputStream(File(filesDir, "run.log"), true).use { out ->
                out.write((s + "\n").toByteArray())
                out.flush()
                out.fd.sync()
            }
        } catch (_: Throwable) {
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
            refreshManagerLabel()
            tvStatus.text = buildString {
                append("Shizuku: ").append(st.shizuku).append('\n')
                when {
                    st.root -> append("root: 已开启 ✅")
                    // 模块在但 su 调不通：多半是 FuseHide / HMA / Shamiko 这类
                    // 隐藏插件把 /system/bin/su 对这条调用链藏起来了，
                    // 不是没 root，所以别直接报「未开启」误导人
                    st.module.contains("kernelsu") ->
                        append("root: 模块已加载，但 su 不可用（检查隐藏插件）")
                    else -> append("root: 未开启")
                }.append('\n')
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
        try {
            File(filesDir, "run.log").writeText("")
        } catch (_: Throwable) {
        }
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
            dismissSuVerifyDialog()
            appendLog(if (ok) "=== 成功：su 已可用 ===" else "=== 失败（看上面日志）===")
            if (ok) showSuccessDialog()
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

    // ---- 验证 su 的进度弹窗（2026-11-07）----
    // exploit 跑完后 App 要轮询 `su -c id` 最多 4 分钟。这段之前完全没有界面反馈，
    // 看到「[5/5] 验证 su ...」之后长时间不动，很容易误判成卡死或失败。
    // 进入 [5/5] 时弹一个不可取消的对话框，用 240 秒为满刻度的进度条显示等待进度。
    private var suDialog: AlertDialog? = null
    private var suDialogBar: ProgressBar? = null
    private var suDialogText: TextView? = null
    private var suDialogStart = 0L
    private val uiHandler = Handler(Looper.getMainLooper())
    private val suTicker = object : Runnable {
        override fun run() {
            val text = suDialogText ?: return
            val bar = suDialogBar ?: return
            val secs = ((System.currentTimeMillis() - suDialogStart) / 1000L).toInt()
            bar.progress = secs.coerceAtMost(240)
            text.text = "已等待 $secs 秒（最多 240 秒）\n\n" +
                "exploit 已经跑完，这一步只是在等 KernelSU 把 su 装好，通常几秒内完成。"
            if (suDialog?.isShowing == true) uiHandler.postDelayed(this, 500L)
        }
    }

    private fun showSuVerifyDialog() {
        if (suDialog?.isShowing == true) return
        suDialogStart = System.currentTimeMillis()
        val pad = (24 * resources.displayMetrics.density).toInt()
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 240
            progress = 0
        }
        val text = TextView(this).apply {
            setPadding(0, pad / 2, 0, 0)
            textSize = 13f
            text = "正在验证 su 权限…"
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
            addView(
                bar,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(text)
        }
        suDialogBar = bar
        suDialogText = text
        suDialog = MaterialAlertDialogBuilder(this)
            .setTitle("等待 root 生效")
            .setView(box)
            .setCancelable(false)
            .show()
        uiHandler.post(suTicker)
    }

    private fun dismissSuVerifyDialog() {
        uiHandler.removeCallbacks(suTicker)
        suDialog?.dismiss()
        suDialog = null
        suDialogBar = null
        suDialogText = null
    }

    /** root 成功后给一个明确的收尾：临时 root 重启即失效，并可直接跳去管理器。 */
    private fun showSuccessDialog() {
        val pkg = currentManagerPkg()
        val msg = buildString {
            append("su 已经可以使用。\n\n")
            append("这是临时 root：KernelSU 模块是 late-load 进去的，重启后失效，重启需要再跑一次。\n\n")
            if (pkg == null) {
                append("没找到已安装的 KernelSU / SukiSU 管理器。")
            } else {
                append("可以在管理器里查看模块授权和 su 规则。")
            }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("root 已就绪")
            .setMessage(msg)
            .setPositiveButton(if (pkg == null) "好" else "打开管理器") { _, _ ->
                if (pkg != null) {
                    val intent = packageManager.getLaunchIntentForPackage(pkg)
                    if (intent == null) {
                        appendLog("  打开 $pkg 失败（没有启动入口）")
                    } else {
                        try {
                            startActivity(intent)
                        } catch (e: Throwable) {
                            appendLog("  打开 $pkg 失败: ${e.message}")
                        }
                    }
                }
            }
            .setNegativeButton(if (pkg == null) null else "稍后", null)
            .show()
    }

    /** 当前生效的管理器：优先用户手动选的，其次按 SukiSU → KernelSU 顺序找已装的。 */
    private fun currentManagerPkg(): String? {
        val sel = RootController.selectedManagerPkg(this)
        val candidates = if (sel.isNotEmpty()) {
            listOf(sel) + RootController.KSU_MANAGERS.map { it.pkg }.filter { it != sel }
        } else {
            RootController.KSU_MANAGERS.map { it.pkg }
        }
        for (p in candidates) {
            if (packageManager.getLaunchIntentForPackage(p) != null) return p
        }
        return null
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

    /**
     * 状态卡片那行的管理器标签：显示「用户选的」和「实际会用的」。
     * 两者不同时必须都写出来 —— 否则用户明明选了 KernelSU、实际却在用
     * SukiSU，会以为开关没生效。
     */
    private suspend fun refreshManagerLabel() {
        val infos = RootController.probeManagers(this)
        val sel = RootController.selectedManagerPkg(this)
        val eff = RootController.effectiveManager(infos, this)
        tvManager.text = when {
            infos.none { it.probed } -> "需先授权 Shizuku"
            infos.none { it.installed } -> "都没装"
            eff == null -> "没有可用的（APK 里读不到 ksud）"
            sel.isEmpty() -> "自动 · ${eff.manager.label}"
            sel == eff.manager.pkg -> eff.manager.label
            else -> "${labelOf(sel, infos)} → 实际 ${eff.manager.label}"
        }
    }

    private fun labelOf(pkg: String, infos: List<RootController.ManagerInfo>): String =
        infos.firstOrNull { it.manager.pkg == pkg }?.manager?.label ?: pkg

    // ---------------- 内核管理器切换 ----------------

    /**
     * 弹选择框让用户指定用哪个管理器抽 ksud。
     *
     * 为什么要这个开关：两个管理器同时装着很常见（换分支没卸旧的），
     * 而 ksud 决定 late-load 加载哪一份 ko —— 选错了就是内核跑 A 家的模块、
     * 用户态用 B 家的 ksud，uapi 对不上，管理器一直报「需要更新内核」。
     * 自动模式按 SukiSU → KernelSU 取第一个能用的，选错了只能靠用户手动指定。
     */
    private fun showManagerPicker() {
        if (running) {
            appendLog("正在跑，别切管理器")
            return
        }
        lifecycleScope.launch {
            val infos = RootController.probeManagers(this@MainActivity)
            val cur = RootController.selectedManagerPkg(this@MainActivity)
            val effective = RootController.effectiveManager(infos, this@MainActivity)

            // 0 = 自动；1..n = 各管理器
            // 单选项的 View 是单行 CheckedTextView，换行符不起作用（会原样显示成
            // 两个字符），所以标签必须压成一行；包名 / APK 路径这些长信息打到日志里。
            val labels = ArrayList<String>()
            labels.add(getString(R.string.manager_auto))
            for (i in infos) {
                val m = i.manager
                val state = when {
                    !i.probed -> "需先授权 Shizuku"
                    i.apk.isEmpty() -> "未安装"
                    !i.usable -> if (i.ksudLib.isEmpty()) "找不到 ksud 本体" else "ksud 太小（${i.ksudSize} B）"
                    else -> buildString {
                        append(i.ksudSize).append(" B")
                        if (i.version.isNotEmpty()) append(" · ").append(i.version)
                    }
                }
                labels.add("${m.label} · $state")
            }
            // 详细信息走日志，不塞进选择框
            for (i in infos) {
                appendLog(
                    "  ${i.manager.label}（${i.manager.pkg}）: " +
                        when {
                            !i.probed -> "未查（Shizuku 未就绪）"
                            i.apk.isEmpty() -> "未安装"
                            else -> "ksud ${i.ksudSize} B · ${i.ksudLib} · ${i.version} · ${i.apk}"
                        }
                )
            }
            val checked = if (cur.isEmpty()) 0 else {
                val idx = infos.indexOfFirst { it.manager.pkg == cur }
                if (idx >= 0) idx + 1 else 0
            }

            var pick = checked
            val dlg = MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(getString(R.string.manager_title))
                .setSingleChoiceItems(
                    labels.toTypedArray(), checked
                ) { _, which -> pick = which }
                .setPositiveButton("确定") { _, _ ->
                    val newPkg = if (pick == 0) "" else infos[pick - 1].manager.pkg
                    RootController.setSelectedManagerPkg(this@MainActivity, newPkg)
                    val nowEffective = RootController.effectiveManager(
                        infos, this@MainActivity
                    )
                    if (newPkg.isEmpty()) {
                        appendLog("管理器：自动 → ${nowEffective?.manager?.label ?: "无可用"}")
                    } else {
                        val chosen = infos[pick - 1]
                        if (chosen.usable) {
                            appendLog("管理器：已切到 ${chosen.manager.label}")
                        } else if (!chosen.probed) {
                            appendLog("管理器：已选中 ${chosen.manager.label}（装没装还没查）")
                        } else {
                            // 选了个取不到 ksud 的：不拦，但要说清楚会顺延
                            appendLog(
                                "管理器：${chosen.manager.label} 取不到 ksud，" +
                                    "实际会用 ${nowEffective?.manager?.label ?: "无可用"}"
                            )
                        }
                    }
                    // 换 ksud 就是换 ko，已经起过内核的话必须先重启，否则模块名
                    // kernelsu 重名，新模块会 File exists 加载失败
                    if (effective?.manager?.pkg != nowEffective?.manager?.pkg &&
                        RootController.moduleLoaded(this@MainActivity)
                    ) {
                        appendLog("注意：内核里已有模块，换管理器必须先重启再开 root")
                    }
                    refresh()
                }
                .setNegativeButton("取消", null)
                .create()
            dlg.show()
            val d = resources.displayMetrics.density
            val bg = GradientDrawable().apply {
                setColor(0xFF1E1E21.toInt())
                cornerRadius = 24f * d
                setStroke((1f * d).toInt(), 0xFF2A2A2E.toInt())
            }
            val inset = (24f * d).toInt()
            dlg.window?.setBackgroundDrawable(InsetDrawable(bg, inset, inset, inset, inset))
        }
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
系统 OS4.0.0.14.XAACNXM 或 OS4.0.5.0.XAACNXM（Android 17）<br>
内核 6.12.69-android16-6-g586bfab1b9c5-abogki536749445-4k<br>
安全补丁 2026-09-01 / 2026-10-01<br><br>

<b>已实测通过的组合</b><br>
REDMI K90 Max / OS4.0.0.14.XAACNXM / 6.12.69-android16-6-g586bfab1b9c5-abogki536749445-4k<br>
2026-10-25 两次成功：<br>
· 冷启动一次过，约 5 分钟，全程无重启<br>
· 中途 panic 重启，靠「断点续跑」第二次成功<br><br>

REDMI K90 Max / OS4.0.5.0.XAACNXM / 6.12.69-android16-6-g586bfab1b9c5-abogki536749445-4k<br>
2026-11-05 两次尝试：<br>
· 第一次 stage0 panic 重启<br>
· 第二次成功，su -c id 返回 uid=0 context=u:r:ksu:s0<br><br>

<b>OS4.0.5.0 的支持依据（已真机验证）</b><br>
OTA 的 boot.img 解出的内核与 4.0.0.14 是同一个二进制：git hash、ABO build id、编译时间全部相同，CVE-2026-64560 的上游修复（920f893f）不在其中。逐地址反汇编核验过 7 处硬编码——release_task 写 sighand=NULL 仍是普通 str、posix_cpu_timer_del 的两个调用点、configfs 两个 iter 入口——全部落在同一偏移。<br>
2026-11-05 真机实测通过：TARGET_PROFILE_GATE_PASS profile=prague-os4.0.5.0 / STAGE0_GATE_PASS slide=0x23fe600000 / ROOT_CHILD_HOLD_PASS / KSU_LOAD_PASS，su -c id 返回 uid=0 context=u:r:ksu:s0。第一次跑 panic 重启，第二次成功。运行日志开头的 <b>TARGET_PROFILE_GATE_PASS profile=prague-os4.0.5.0</b> 可用于确认版本识别。<br><br>

<b>其他机型或内核版本：不支持，请勿尝试。</b><br>
本工具用的内核地址、结构体偏移全部是在上面这一版内核上实测出来的。换一个内核这些值就失效，写下去等于直接改坏内核内存，结果是当场 panic 或随机重启。判断标准看 <b>uname -r</b> 和 ABO build id，不是看系统版本号。<br><br>

<b>项目限制</b><br>
· 临时 root：重启即失效，不写 boot 分区，不做持久化<br>
· 前提：Shizuku（或 Shevery 等兼容实现）已在运行 + KernelSU 或 SukiSU Ultra 管理器已安装<br>
· 约 40% 概率触发内核 panic 并重启（实测 17 次里 7 次）<br>· 系统负载高时失败率更高。状态行会显示当前负载，<b>「偏高」时建议先等一等</b><br>
· 成功后内核里会留一颗 dangling timer，之后可能随机崩溃，重启清除<br>
· 不提供 Magisk 模块、Zygisk、隐藏、开机自启<br>
· 仅在 prague 这一款设备上验证过<br><br>

<b>支持的管理器</b><br>
KernelSU 与 SukiSU Ultra <b>都可用</b>：ksud 在运行期从已装管理器里提取，优先 SukiSU，取不到再退回 KernelSU。两者是同源分支，late-load / feature 的 CLI 完全一致，所以脚本通用。注意 <b>ksud 与内核模块必须来自同一个管理器</b>——late-load 加载的就是 ksud 自带的那一份 ko，混用会 uapi 不匹配。<br>
两个都装时，可以在主界面点「管理器」那行手动指定用哪一个。切换后 <b>必须先重启再开 root</b>：两家的模块名都叫 kernelsu，老模块还在内核里时新模块会加载失败。<br><br>

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
SukiSU Ultra <a href="https://github.com/SukiSU-Ultra/SukiSU-Ultra">SukiSU-Ultra/SukiSU-Ultra</a>（GPL-3.0，运行期从已装管理器提取）<br>
Shizuku <a href="https://github.com/RikkaApps/Shizuku">RikkaApps/Shizuku</a>（Apache-2.0）<br>
漏洞 <a href="https://nvd.nist.gov/vuln/detail/CVE-2026-64560">CVE-2026-64560</a>（posix-cpu-timers UAF）
        """
    }
}
