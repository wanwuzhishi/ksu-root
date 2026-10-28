# Changelog

## [1.0.3] - 2026-10-28

> 本版把开发过程中的 1.0.3 ~ 1.0.7 合并发布：中间那些版本号没有对外发过，
> 用户看到的发布序列只有 1.0.0 / 1.0.1 / 1.0.2 / 1.0.3 四个。

### 新增

- **断点续跑**：跑之前记一笔状态，被系统杀掉或设备重启后，下次打开 App 会把按钮换成「继续上次（已中断）」并告诉你停在哪一步
- **导出日志 / 复制日志**：日志写到 `下载/ksu-root-<时间>.txt`，或一键进剪贴板
- **运行日志实时落盘**：每行同步写 `files/run.log`，panic 后也能拿到崩前的日志
- **进度条**：按 `[N/5]` 阶段推进（8/25/45/60/92/100），`[4/5]` 那段额外解析 `ATTEMPT_BEGIN attempt=N` 让它在 60→90 继续走
- **状态卡片显示系统负载**：`load1 > 8` 提示「偏高」，刚开机 `uptime < 300s` 提示「建议等 5 分钟」
- **关于页**：项目地址、适配机型、实测通过的系统与内核、项目限制、免责声明、致谢（带链接）
- **兼容第三方 Shizuku 实现**：官方 Shizuku / Shevery 已实测；Shizuku-Next、yangFenTuoZi/Shizuku、ShizukuPlus 的 dropin 版同包名同权限名理论可用；Nightzuku 和 ShizukuPlus 独立版权限名不同，状态行会显示「版本不兼容」
- **失败后必须重启的硬拦截**：上一次失败且没重启过就再跑，会在 `misc_open` 阶段 panic。现在直接拒绝并提示重启

### 修复

- **`ShizukuHelper.exec()` 泄漏 pipe**：每条 Shizuku 命令都是一对 pipe，之前只在超时那一刻 `destroyForcibly()`，正常返回路径上流和远程进程都交给 GC——而 GC 什么时候跑不确定。这些 pipe 挂在 `shell` 用户头上，exploit 后期 `F_SETPIPE_SZ` 要占 117×128=14976 页（`pipe-user-pages-soft` 默认 16384 页），几个泄漏就足以把余量吃干净
  - 表现：`PIPE_PREP_FAIL stage=grow_reclaim index=72/73/77/78 errno=1`（EPERM）
  - 修法：`finally` 里显式 `close()` 三条流 + `destroy()` 回收远程进程
- **`ksud` 校验只看大小**：`cp` 静默失败会留下**大小完全正确、内容全 `0x00`** 的垃圾文件，执行不报错、`late-load` 返回 0、`debug info` 却连不上内核，一路「成功」到最后 `su` 不存在。改成校验 ELF 魔数 `7f454c46`，不对就用 `cat` 流重写并复查
- **`ksud` 不跟随管理器版本**：原来「已存在且 ≥4MB 就直接用」，换了管理器还吃旧 ksud。改成按管理器 APK 里 `lib/arm64/libksud.so` 的实际大小比对，不一致就重抽
- **Kotlin 转义写错**：正则 `Regex("\u001b\\[[0-9;]*m")` 编译成非法字符类（`Missing closing bracket`）；注入命令用的 `"
"` 是字面反斜杠 + n，root shell 收不到回车，命令根本不执行
- **`ShizukuRemoteProcess` 的 `waitFor` / `exitValue` 不可信**：`waitFor(300s)` 在进程已退出、文件已完整落盘时仍返回 false，`exitValue()` 抛 `IllegalThreadStateException("process hasn't exited")`。改成「读流 join 判结束」+「按文件大小轮询判写入」
- **dirty 状态的判据**：原来用 `elapsedRealtime() >= dirtyBoot` 判「没重启」，只在「重启了」一侧成立，重启后隔几分钟再开 App 会被误判成「没重启」而一直被拦。改成比 `bootWallTime() = currentTimeMillis() - elapsedRealtime()`，容差 10 秒
- **中断提示重复打印**：`checkPending()` 被 `onResume` 反复触发，同一笔提示打两遍
- **`su` 被隐藏插件屏蔽时误报「未开启」**：FuseHide / HMA / Shamiko 这类 Zygisk 隐藏模块会把 `/system/bin/su` 对特定调用链藏起来。此时内核模块确实加载了、`adb shell` 里 `su` 也能用，但 App 经 Shizuku fork 出来的进程看不到 `su`，状态行就误报「未开启」。改成显示「模块已加载，但 su 不可用（检查隐藏插件）」

### 实测

改完重启一次、第二次成功：`su -c id` → `uid=0(root) gid=0(root) groups=0(root) context=u:r:ksu:s0`，`grep -i kernelsu /proc/modules` → `kernelsu 221184 1 - Live`，`/system/bin/su` 4990848 B，`getenforce` → `Enforcing`。

> 诚实说明：不能断言就是 pipe 泄漏这一处修复的功劳——之前也重试过多次。但修之前连续 5 次失败（3 次 `PIPE_PREP_FAIL` + 2 次 stage0 panic），修之后 2 次内成功。另外 `ksud` 的 NUL 文件问题当时确实卡住了整个流程。

### 构建 CI

- 修 GitHub Actions 一直红的问题：`android-actions/setup-android@v3 → v4`（v3 会找已删除的 `tools` 包）、`actions/upload-artifact@v5 → v6`（v5 仍是 node20）、`gradlew` 补 `100755` 权限
- `runs-on` 钉到 `ubuntu-24.04`

## [1.0.2] - 2026-10-26

### 新增

- **状态卡片显示系统负载**：读 `/proc/loadavg` 和 `/proc/uptime`，`load1 > 8` 或开机不满 5 分钟时提示「偏高，建议等系统闲下来再开」。之前只在日志里提示，要跑到 `[1/5]` 才看得到，等于没有

### 文档

- README 新增「成功率跟系统负载强相关」一节，含 panic 栈样例、判据表和四条降低失败率的做法
- AI-GUIDE 新增 3.2 节，要求 AI 在跑之前先查 `load` 和 `uptime`

### 修复

- 同一笔「上次运行被打断」的记录被 `onResume` 反复提示，加 `pendingNotified` 去重

## [1.0.1] - 2026-10-26

### 新增

- **兼容多个第三方 Shizuku 实现**：官方、Shevery、Shizuku-Next、yangFenTuoZi/Shizuku、ShizukuPlus 的 dropin 版。判断依据是包名无关、权限名相关的两条硬条件
- 状态行新增「版本不兼容」：装了 Nightzuku 或 ShizukuPlus 独立版（把 `moe.shizuku.manager.permission.API_V23` 改成自己前缀）时直接提示，不再干等到「未授权」
- 进度条：按 `[N/5]` 阶段推进（8/25/45/60/92/100），`[4/5]` 那段额外解析 `ATTEMPT_BEGIN attempt=N` 让它在 60→90 继续走，结束填满后 1.5 秒自动隐藏
- 关于页增加「支持的 Shizuku 实现」一段

### 修复

- **`writeViaStdin` 误报写入失败**：原来靠 `ShizukuRemoteProcess.waitFor(timeout, unit)` 的返回值判断，该值实测不可信（文件完整落盘仍返回 false）。改成写完轮询文件大小
- 关于页版本号写死成 `v1.0.0`，改成读 `BuildConfig.VERSION_NAME`（同时开 `buildFeatures.buildConfig`）
- 空闲状态误显示 60% 进度：断点续跑提示日志里带「[4/5]」被解析器抓到，加 `running` 守卫

### 构建 / CI

- GitHub Actions 全部升到 node24 运行时：`checkout@v5`、`setup-java@v5`、`upload-artifact@v6`、`setup-android@v4`、`setup-gradle@v5`
- `runs-on` 从 `ubuntu-latest` 钉到 `ubuntu-24.04`（2026-10-19 后 latest 会迁到 Ubuntu 26）
- 修 `gradlew` 在 git 索引里是 100644 导致 Linux runner `Permission denied`
- 修 `setup-android@v3` 会执行 `sdkmanager "tools"`、而该包在新版 SDK 中已不存在导致的失败
- Gradle wrapper 指向腾讯镜像

## [1.0.0] - 2026-10-25

首个可用版本。已在真机（REDMI K90 Max / OS4.0.0.14）上两次实测拿到 `su`。

### 功能

- 一键开临时 root：部署载荷 → 布防 kprobe → 跑 exploit → 注入 ksud → 验证 `su`
- 状态卡片：Shizuku / root / SELinux / 内核模块
- 高风险操作二次确认弹窗（列出可能现象、后果、免责声明）
- 断点续跑：panic 重启后自动识别"被重启打断"，主按钮变「继续上次（已中断）」
- 日志导出到 Downloads、一键复制到剪贴板
- 实时日志区（5 秒批量刷新，最多保留 2000 行）
- 关于页：项目地址、适配机型、实测通过的系统与内核版本、项目限制、免责声明、致谢
- 进度条：按 `[N/5]` 阶段推进（8/25/45/60/92/100），`[4/5]` 那段额外解析 `ATTEMPT_BEGIN attempt=N` 让它在 60→90 继续走，结束填满后 1.5 秒自动隐藏

### 技术决策

- **自己写 `ptyrun.c` 替代 busybox**：上游 adb 流程依赖 `busybox script` 分配 pty，但 busybox 是 1.4 MB 的 GPL-2.0 二进制。自己实现（约 130 行 C，静态链接 421 KB）后，全仓库只剩 Apache-2.0
- **ksud 不打包**：运行时从用户已装的 KernelSU 管理器 APK 抽取 `lib/arm64/libksud.so`。既避免分发 GPL-3.0 二进制，又保证用户态与内核模块的 uapi 版本永远一致
- **Shizuku API 锁 12.2.0**：13.x 的 `Shizuku.newProcess()` 是 `private`，外部无法构造远程进程

### 已修复的坑

- Shizuku `exec` 拿输出的三种错误写法（`readText` 提前 EOF / 立即 `waitFor` / `exitValue()` 抛 `IllegalThreadStateException`），最终改为双线程读流 + `join` 判断结束，不碰 `waitFor`/`exitValue`
- Kotlin 转义错误：正则 `Regex("\\u001b\\\\[...")` 导致 `Missing closing bracket in character class`；注入命令用 `"...\\n"` 写成了字面 `\n` 两字符，导致 root shell 收不到回车
- 载荷被写成等长全 NUL：`writeViaStdin` 失败过一次，795 字节全变 `\0`，`test -f` 检查不出来。改为比对字节数 + 重试
- `ensureFiles` 把多个 `stat` 拆成多次 `exec`，失败概率叠加，改为一条命令批量取

### 已知限制

- 只支持 `Redmi/prague/prague:17/CP2A.260605.016/OS4.0.0.14.XAACNXM` 这一个系统版本
- 重启即失效，无持久化
- Shizuku 每次重启要手动启动（开机自启实测不可靠）
- 约 40% 概率在 `stage0` 触发内核 panic
- 开过 root 后内核里留 dangling timer，可能随机 panic 重启，重启一次清除
