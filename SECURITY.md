# 安全说明

## 这个项目本身的性质

这是一个**本地提权工具**，它故意利用 `CVE-2026-64560`（`posix-cpu-timers` 的 non-leader `exec()` 竞态导致的 UAF）在内核内存里建立任意读写，然后覆写当前进程的 `cred` 结构和 SELinux 状态。

它会：

- 改写内核数据结构（`random_table[4].data`、`misc_list`、`pipe_buffer`、`cred`）
- 短暂把 SELinux 设为 Permissive
- 在内核里留下 dangling timer 节点（可能导致之后随机 panic）

**如果你不能接受这些，请不要使用。**

## 它不会做的事

- 不上传任何数据（没有网络权限）
- 不修改 `/system`、`/vendor`、`/boot`
- 不持久化（重启即失效）
- 不安装后台服务、不开机自启

## 报告问题

如果你发现本项目**自身代码**的安全问题（比如日志导出路径穿越、Shizuku 调用被劫持、载荷校验绕过），请开 issue。

如果你发现的是**上游利用链**的问题（`pk64560` 的漏洞），请报告给 [ChaYeWuu/prague-CVE-2026-64560-OS4.0](https://github.com/ChaYeWuu/prague-CVE-2026-64560-OS4.0)。

如果你发现的是**KernelSU / Shizuku** 的问题，请报告给对应上游。

## 载荷完整性

App 在部署载荷时会比对每个文件的字节数，与 APK 内 `assets/` 中的不一致就重写，最多重试 3 次。

`ksud` 不随包分发，运行时从用户设备上已安装的 KernelSU 管理器 APK 中提取——因此它的内容取决于用户装的是哪个版本，本项目不做校验（也不应该做，否则版本一升级就坏）。

## 已知的"看起来像安全问题，其实不是"

| 现象 | 解释 |
|---|---|
| `/proc/sys/kernel/random/boot_id` 变成 `d8f06902-80ff-ffff-...` | `stage0` 改写了 `random_table[4].data`，这是设计内的副作用 |
| 之后某个 App 崩溃导致整机重启 | 内核里的 dangling timer 被 `timer_delete` 触发，重启即清除 |
| panic 重启后 SELinux 是 Permissive | MTK AEE 在连续 panic 后的调试态，再正常重启一次恢复 |
| `su` 存在但 `ls /data/adb` 报 Permission denied | 普通 shell 域（`u:r:shell:s0`）无权访问；要 `su` 进去才是 `u:r:ksu:s0` |
