# Changelog

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
