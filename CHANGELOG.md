# Changelog

## [1.1.0] - 2026-11-06

### 修复

- **干净失败被误标成「脏」**。exploit 在 stage0 之后任何一步失败都会走 `bridge_cleanup` 把内核复原，并打印 `KERNEL_STATE_RESTORE_PASS dirty=0`。但 App 只看「有没有触过内核」，照样标脏，下次点按钮就被「上次失败后没重启」拦住 —— 内核其实是干净的，白让用户重启一次。
  现在引入 `ExploitOutcome` 三态：`GATE_REFUSED`（改写内核之前就退出，如指纹不匹配）、`CLEAN_RESTORE`（动过但已复原确认）、`TOUCHED`（状态不可知）。只有 `TOUCHED` 才标脏。
- **失败后白等 4 分钟**。exploit 在 `PIPE_CACHE_GATE` 这类位置失败时，根本不会进 root shell，也就不可能加载 LKM，`su` 不可能凭空出现。但 `[5/5]` 照样轮询满 `SU_VERIFY_TIMEOUT_MS`。现在 `runExploit` 一并返回 `reachedRootShell`，没进 root shell 直接跳过 su 验证。
- **`ls /system/bin/su` 的判据误命中**。文件不存在时 `ls` 的输出是 `ls: /system/bin/su: No such file or directory`，`contains("/system/bin/su")` 必然为真，于是每次都误判「su 已存在但调用被拒」，白跑一次 `ksud feature set shell_su_adb_only 0`，日志里还会打出误导性的「su 已存在但调用被拒」。改用 `[ -e /system/bin/su ] && echo SU_EXISTS || echo SU_MISSING`。

### 变更

- `runSteps` 的参数从 `onKernelTouch` / `onKernelClean` 两个回调合并为一个 `onOutcome`，交给调用方按三态决策，不再在流程中间靠副作用翻转布尔量。
- `runExploit` 返回值从 `Boolean` 改为 `ExploitResult(outcome, reachedRootShell)`。

## [1.0.9] - 2026-11-06

### 新增

- **可以手动切换内核管理器**：状态卡片下方新增「管理器」一行，点开是单选列表：
  - **自动（SukiSU → KernelSU）** —— 默认，按顺序取第一个能用的
  - **SukiSU Ultra（com.sukisu.ultra）** / **KernelSU（me.weishu.kernelsu）** —— 指定后优先用它

  每一项都显示从该管理器 APK 里读到的 ksud 大小与版号，未安装 / 读不到 ksud 会直接标出来。选择存在 SharedPreferences 里，重启 App 和重启设备都保留。详情（ksud 路径、lib 文件名、APK 路径）打在日志里，不塞进选择框 —— 单选控件是单行的，塞不下。

### 为什么需要这个开关

换 `ksud` 就是换内核模块：`late-load` 加载的是 ksud 自带的那一份 ko。两个管理器同时装着很常见（换分支没卸旧的），自动模式按 SukiSU → KernelSU 取第一个能用的看起来总是对的，但用户可能就是想用 KernelSU，或者装了两个 SukiSU 系的分支包。之前只能靠卸载来改变顺序。

选了取不到 ksud 的那一项**不会卡死**：只影响优先级，实际会顺延到后面可用的候选，日志会写明「XX 取不到 ksud，实际会用 YY」。

### 交互细节

- 探测前先判断 Shizuku 是否可用。未授权时 `pm path` 返回空，旧逻辑会把它当成「都没装」，UI 上就会莫名其妙地说两个管理器都没装 —— 现在显示「需先授权 Shizuku」，选择框里写「需先授权 Shizuku 才能查」。
- 换管理器时如果内核里已有 `kernelsu` 模块，日志会提示「必须先重启再开 root」：两家的模块名都叫 `kernelsu`，老的还在，新的 insmod 会 `File exists (os error 17)`（实测过，vermagic 检查是通过的）。
- 运行中不允许切换（会打断日志里「用哪个 ksud」的对应关系）。

### 验证记录（2026-11-06，设备 `958DJBWGV8ONDM4P`，OS4.0.5.0）

- 三个选项都正确渲染，单选状态跟着实际选择走。
- 选 KernelSU → 写盘 `manager_pkg=me.weishu.kernelsu` → 状态行变「管理器 KernelSU」。
- 选回自动 → 写盘 `manager_pkg=`（空串）→ 状态行变「管理器 自动 · SukiSU Ultra」。
- **未验证**：切换后真正跑一次 root（需要重启清空内核，见 README）。

## [1.0.8] - 2026-11-05

### 新增

- **支持 SukiSU Ultra 管理器**：ksud 候选表从单一 `me.weishu.kernelsu` 改为优先级列表，SukiSU（`com.sukisu.ultra`）在前、KernelSU 兜底。两者的 ksud 都改名伪装成 native 库放在 APK 里，文件名不同：

  | 管理器 | 包名 | ksud 在 APK 内 |
  |---|---|---|
  | SukiSU Ultra | `com.sukisu.ultra` | `lib/arm64/libfdbqiupsrx.so`（6,102,016 B，ksud 4.3.0-r1） |
  | KernelSU | `me.weishu.kernelsu` | `lib/arm64/libksud.so`（5,172,384 B，ksud 3.3.1） |

  两者同源分支，`late-load --allow-shell`、`feature get/set/list/check/load/save`、`debug info` 的参数名与输出格式完全一致，所以 `ksu_load_setup.sh` 只改了一行注释和一行 `ksud -V` 打印，逻辑未动。

### 修复

- **去掉「已有够大的 ksud 就跳过提取」的短路**：用户从 KernelSU 换到 SukiSU 之后，workdir 里旧的 5 MB ksud 满足「够大」，旧逻辑直接过关，于是 `late-load` 加载的还是老分支的 ko，管理器一直报 uapi 不匹配。现在每次运行都重写一遍 ksud，保证 workdir 里的永远等于当前选中的管理器。

### 验证记录（2026-11-05，设备 `958DJBWGV8ONDM4P`，OS4.0.5.0）

- SukiSU 的 ko（`lkm-aarch64-android16-6.12_kernelsu.ko`，648,056 B，vermagic `6.12.76-4k`）对本机内核（`6.12.69-android16-6-...`）的 **251 个 undefined 符号全部可解析，0 缺失**。
- 用 `sukisu-ksud boot-patch-v2` 对 4.0.5.0 的 boot.img 做离线 patch 验证（**没有刷入任何分区，产物已删**）：`Module fixups: 251, unresolved: 0`，KMI 自动识别为 `android16-6.12`，BTF v1 168480 types、Kallsyms 6.4+ 131357 symbols 全部读通。
- 直接 `insmod` SukiSU 的 ko 报 `init_module failed without vermagic mismatch` + `File exists (os error 17)` —— **vermagic 检查通过**，唯一阻碍是模块名 `kernelsu` 与已加载的 KernelSU 模块重名。也就是说换分支时**必须先卸载旧模块或重启**，不能在已有 KernelSU 模块的内核上叠加。
- **未验证**：完整的 App 端一键开 root 在 SukiSU 分支下的端到端流程（需要重启清空内核后跑一次，见 README 的限制说明）。

## [1.0.7] - 2026-11-05

> 本版把 1.0.6 和 1.0.7 合并发布。

### 修复

- **`su` 被 `shell_su_adb_only` 拦住，导致成功被判成失败（本版最重要）**：KernelSU 的 `shell_su_adb_only`（feature ID=6）默认开启，只放行**由 adbd 或 ksu 域派生**的进程使用 `su`。本 App 的调用链是 App → Shizuku → `shizuku_server`，而 `shizuku_server` 是用 `libshizuku.so` 经 `setsid` 起的、父进程被 init 接管（`ppid=1`），不在放行范围内，表现为 `sh: su: inaccessible or not found`——连 `/system/bin/su` 的 exec 都被拦掉（不是权限不足，是「找不到」）。
  - 对照实验（同一台设备同一个 boot，只切 feature）：

    | `shell_su_adb_only` | 进程父级 | `su -c id` |
    |---|---|---|
    | `1` | `ppid=1` | `sh: su: inaccessible or not found` |
    | `0` | `ppid=1` | `uid=0(root) gid=0(root) groups=0(root) context=u:r:ksu:s0` |

  - 之前一直没发现是因为**在 adb 端验证时父进程就是 adbd**，恰好落在放行范围内，每次都对；只有 App 这条链会失败。这个假象很容易把问题误判成「`su` 还没生成」。
  - 修复：注入脚本里执行 `ksud feature set shell_su_adb_only 0`；App 侧再加一层兜底——若 `/system/bin/su` 已存在但调用被拒，就再关一次 feature 然后重试。
- **`[5/5] 验证 su` 只查一次就下结论**：`su` 出现在 `/system/bin/su` 之后仍可能因为上面那条 feature 而调不通，原来的实现查一次失败就直接判失败。改成轮询（最多 4 分钟、每 5 秒一次），并在 30 秒时触发上面那层兜底。
- **失败误标脏**：上面两个问题叠加会让「实际已经 root 成功」的运行被判失败并写入 `dirty=true`，用户再点按钮就被拦下来要求重启——而重启会把好不容易拿到的 root 扔掉。本版修复后不会再出现这种误判（`v1.0.6` 引入的 `kernelTouched` 跟踪仍然生效，只保证「内核没被写过」的失败不标脏）。

### 已验证

- 真机（Redmi K90 Max / prague / OS4.0.5.0.XAACNXM）实测：exploit 全链跑通后 `su -c id` → `uid=0(root) ... u:r:ksu:s0`，`kernelsu 221184 1 - Live`，`/system/bin/su` 4990848 B root:root，App 状态卡片显示「root: 已开启 ✅」

## [1.0.5] - 2026-11-05

### 修复

- **OS4.0.5.0 正式版指纹识别**：v1.0.4 按 OTA `META-INF/com/android/metadata` 的 `post-build` 加了 `17OS4.0.261020.233332783.MTPECN.S`，但**真机安装后 `ro.build.fingerprint` 报的是 `OS4.0.5.0.XAACNXM`**（metadata 里是预发布串）。结果 v1.0.4 在正式版 4.0.5.0 上会被 `TARGET_PROFILE_GATE_FAIL` 拒绝。v1.0.5 同时认两个串。

### 已验证

- 真机（Redmi K90 Max / prague / OS4.0.5.0.XAACNXM / 内核 6.12.69-android16-6-g586bfab1b9c5-abogki536749445-4k）实测通过：`TARGET_PROFILE_GATE_PASS profile=prague-os4.0.5.0`、`STAGE0_GATE_PASS slide=0x23fe600000`、`ROOT_CHILD_HOLD_PASS`、`KSU_LOAD_PASS`，最终 `su -c id` → `uid=0(root) context=u:r:ksu:s0`，`kernelsu 221184 0 - Live`
- 第一次实跑 panic 重启（`run405.log` 15 KB 全 NUL，boot_id 变化），第二次成功——与既有约 40% panic 率一致

### 载荷

- `pk64560` = 125944 B，sha256 `ed1f1266c3998854d91de3179159f3c689ea19c59b6ec2febd2fd7610926e711`

## [1.0.4] - 2026-11-04

### 新增

- **支持 OS4.0.5.0.XAACNXM**：`detect_standalone_profile()` 增加第二个 prague 指纹 `Redmi/prague/prague:17/CP2A.260605.016/17OS4.0.261020.233332783.MTPECN.S:user/release-keys`，日志里显示 `profile=prague-os4.0.5.0` 以区分

### 说明

- 新系统**没有重编内核**。把 OTA（8.96 GB full payload）里 `boot` 分区的 REPLACE_XZ 数据逐块解出、再过 LZ4 解压得到的 `Image`，版本串仍是 `6.12.69-android16-6-g586bfab1b9c5-abogki536749445-4k`，编译时间 `#1 SMP PREEMPT Mon Jul 20 18:34:35 UTC 2026`，ABO build id 相同
- 上游 CVE-2026-64560 的修复（`920f893f`，`smp_store_release(&tsk->sighand, NULL)`）**不在其中**，`release_task` 里仍是普通 `str xzr, [x19, #0x960]`
- 逐地址反汇编核验过 9 处硬编码偏移（`0xe65b0`、`0x1fb67c`、`0x1fb6bc`、`0x1fb6d0`、`0x4707f4`、`0x470884`、`0x53d35c`、`0x53d908`、`0xf7394`），全部吻合；`vendor_boot` 的 bootconfig 里 `rcu_nocbs=all` / `rcutree.enable_rcu_lazy=1` 也保留
- **没有在真机上跑过 OS4.0.5.0**，只做了离线二进制比对。用户空间的 SELinux / tracefs / `/dev/uhid` 权限藏在 system、vendor 的 EROFS 里，离线验证不到，所以第一次跑请先看 `--preflight` 的输出
- 构建可复现性已验证：用 NDK r29 的 `aarch64-linux-android35-clang` 编译未修改的源码，产物与原作者 `payload/libexploit.so` **逐字节一致**（`b0fd0edd…`，125296 B）。本版改动后 125688 B，114 个函数里 113 个代码大小完全不变，唯一变化的是 `main`（+124 B，多出的 profile 判断被内联进去），全部竞态热路径一字未动

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
