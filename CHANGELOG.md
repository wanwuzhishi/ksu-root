# Changelog

## [1.0.3] - 2026-10-26

### 修复

- **失败的运行会留下坏 miscdevice 节点**：`PIPE_PREP_FAIL` 之后 `restore_misc_list` 只把链表头指回去，中间那个伪造的 `miscdevice` 没摘干净，它指向已释放的内存。不重启就再跑，下一次 `misc_open` 遍历链表必然踩到——实测 pstore 里就是 `misc_open+0x4c` 崩的，寄存器 `x23` 正是上一轮的 `misc_list` head
- **加硬拦截**：跑之前检查上次是不是失败退出且还没重启过。是的话直接拒绝并提示重启，不再让用户白跑一趟
- **修「负载 0.0 · 偏高」的矛盾显示**：刚开机和负载高是两件事，拆成「刚开机 Ns，建议等 5 分钟」和「偏高，建议等系统闲下来再开」两种文案

### 说明

这次改动源于一次真实踩坑：上一轮 `PIPE_PREP_FAIL` 是「干净失败」（没 panic、`KERNEL_STATE_RESTORE_PASS dirty=0`），用户没重启就再点了一次，12 分钟后 `misc_open` panic。**干净失败留下的脏东西比 panic 还阴，它不立即崩，等下一次才开始咬人。**

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
