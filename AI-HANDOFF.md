# AI 交接文档 — KSU Root（prague / CVE-2026-98260）

> 这份文档是写给**接手这个仓库的 AI 助手**看的，不是给最终用户看的。
> 目标是让你在 20 分钟内理解：这个 App 在做什么、代码怎么组织、哪些地方是雷区、当前进度到哪、下一步该干什么。
>
> 最后更新：2026-11-21（对应 versionCode 42 / v1.2.10）

---

## 1. 一句话说明

一个**小米 REDMI K90 Max（内部代号 `prague`）专用**的一键临时 root App。
用户点「开启 root」，App 通过 Shizuku 拿到 `shell` 权限，跑一个上游预编译的 exploit 二进制，利用 **CVE-2026-98260**（POSIX CPU timer 的 UAF）拿到 uid=0，然后加载 SukiSU Ultra 的内核模块（LKM），最终让 `/system/bin/su` 可用。

**临时 root**：LKM 是 `finit_module` 动态加载的、没有签名也没有持久化，**重启即失效**。这是有意为之 —— 设备 BL 锁死（`ro.boot.flash.locked=1`），不能刷 boot.img，只能走这种运行时加载。

**只支持这一款设备、这一个内核版本**。指纹不匹配会直接 `TARGET_PROFILE_GATE_FAIL` 退出，这是设计行为，不是 bug。

---

## 2. 环境与路径

| 项 | 值 |
|---|---|
| 仓库路径 | `F:\agent\.dsh\ungrouped\ksu-root-apk` |
| 目标设备 | REDMI K90 Max，`prague` / `2604FRK1EC`，adb 序列号 `958DJBWGV8ONDM4P` |
| 系统 | OS4.0.5.0.XAACNXM（Android 17 / SDK 37） |
| 内核 | `6.12.69-android16-6-g586bfab1b9c5-abogki536749445-4k` |
| 指纹 | `Redmi/prague/prague:17/CP2A.260605.016/OS4.0.5.0.XAACNXM:user/release-keys` |
| Android SDK | `D:\dev\android-sdk` |
| NDK | `D:\dev\android-sdk\ndk\28.2.13676358`（r28，注意 README 里写的是 r29，以实际为准） |
| Gradle | `./gradlew`（wrapper 已在仓库内） |
| 构建产物 | `app/build/outputs/apk/debug/app-debug.apk` |

**只编译 Kotlin（改 UI）**：
```bash
cd "F:/agent/.dsh/ungrouped/ksu-root-apk"
./gradlew compileDebugKotlin --no-daemon 2>&1 | grep -E "^e:"
```
**出 APK**：
```bash
./gradlew assembleDebug --no-daemon -q
ls -l app/build/outputs/apk/debug/app-debug.apk && sha256sum app/build/outputs/apk/debug/app-debug.apk
```
**装机**：
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell dumpsys package com.ksuroot.app | grep -E "versionCode|versionName" | head -2
```

> 构建时**不要**用 `-q` 去 grep `BUILD SUCCESSFUL` 判断改没改上 —— 见 §7 的「patch 静默失败」条目。

---

## 3. 架构与数据流

```
MainActivity (UI, Compose 不用，是传统 View + XML)
  │  状态卡 / 管理器选择 / 开启 root / 导出日志
  ▼
RootController (编排，全部 suspend 函数)
  │  runSteps() 五个阶段
  ▼
ShizukuHelper (以 shell uid=2000 执行命令)
  │  exec(cmd, timeoutSec) → stdout+stderr 合并字符串
  ▼
ptyrun (app/src/main/cpp/ptyrun.c, 预编译 421,512 B)
  │  用 forkpty() 起子进程，把 pty 输出转给 stdout
  │  为什么需要它：exploit 里会调用 setsid/ioctl 拿控制终端，
  │  直接 Runtime.exec() 起的话没有 pty，行为会不一样
  ▼
pk64560 (上游预编译 ET_EXEC, 2,296,288 B)
  │  CVE-2026-98260 提权链，详见 §6
  │  成功后起一个 interactive root child（常驻）
  ▼
ksu_load_setup.sh (被注入到 root child 执行)
  │  检查模块状态 → 必要时 ksud late-load → 等 su → 关 shell_su_adb_only
  ▼
sukisu-prague.ko (646,392 B, 由 exploit 或 ksud 加载)
```

### 五个阶段（`RootController.kt:377-475`）

| 阶段 | 做什么 | 失败会怎样 |
|---|---|---|
| `[1/5]` 检查 Shizuku | `Shizuku.pingBinder()` + 权限检查 | 直接返回，提示装 Shizuku |
| `[2/5]` 部署载荷 | `mkdir WORK_DIR` → 逐个 asset 比对 **sha256** → 不一致就重写 → `chmod 755` → 抽 ksud | 返回，不碰内核 |
| `[3/5]` 检查内核环境 | `grep -ci kernelsu /proc/modules`（只读，**失败不阻断**） | 仅日志 |
| `[4/5]` 跑 exploit | `ptyrun pk64560 --run`，独立线程读 + 队列轮询 | 按 `ExploitOutcome` 决定标不标 dirty |
| `[5/5]` 验证 su | 每 5 秒跑一次 `su -c id`，最多 4 分钟 | 提示重启 |

**关键设计**：`[4/5]` 结束后不看退出码，看日志里有没有 `KERNEL_STATE_RESTORE_PASS dirty=0` —— 因为 exploit 在 stage0 之后任何一步失败都会走 `bridge_cleanup` 复原内核，退出码一样但内核状态天差地别。这个判断在 `ExploitOutcome` 枚举里（`GATE_REFUSED` / `CLEAN_RESTORE` / `TOUCHED`）。

---

## 4. 文件地图

```
app/src/main/
├── AndroidManifest.xml          # 有 <queries> 声明（见 §7 坑 12）
├── assets/
│   ├── pk64560                  # 2,296,288 B  上游 98260 payload（核心，见 §6）
│   ├── sukisu-prague.ko         # 646,392 B    SukiSU 的 LKM（6.12.69 编译）
│   ├── ptyrun                   # 421,512 B    伪终端包装器（预编译）
│   ├── kp_arm.sh                # 647 B        kprobe 布防脚本（v1.2.x 已不用，保留兼容）
│   └── ksu_load_setup.sh        # 2,978 B      root shell 里执行的收尾脚本
├── cpp/ptyrun.c                 # ptyrun 的源码（改了要重新交叉编译再覆盖 assets/ptyrun）
├── java/com/ksuroot/app/
│   ├── MainActivity.kt          # 757 行  UI 全部逻辑 + ABOUT_HTML + 成功弹窗
│   ├── RootController.kt        # 865 行  全部编排逻辑 + 文件部署 + 日志解析
│   └── ShizukuHelper.kt         # 129 行   Shizuku 进程封装（有超时竞态，见 §7 坑 14）
└── res/
    ├── layout/activity_main.xml # 状态卡 / rowManager / 五个按钮 / 日志区
    ├── values/strings.xml
    ├── values/colors.xml / themes.xml
    └── drawable/*.xml           # 圆角背景、按钮描边

仓库根目录
├── README.md          # 面向用户的项目介绍（19610 B）
├── AI-GUIDE.md        # 面向 AI 的排查手册（17641 B）—— 与本文档互补，那份偏「故障判据」
├── CHANGELOG.md       # 全量版本历史（56666 B，从 v1.0.0 到 v1.2.10）
├── NOTICE             # 上游归属与许可证（3675 B）
├── SECURITY.md
└── docs/screenshots/
```

---

## 5. 关键常量

```kotlin
// RootController.kt
const val WORK_DIR = "/data/local/tmp/ksu-root"       // :29  设备上的工作目录
private val ASSETS = listOf(                          // :30
    "pk64560", "ptyrun", "kp_arm.sh", "ksu_load_setup.sh", "sukisu-prague.ko"
)
private const val KSUD_MIN_BYTES = 4_000_000L         // :32  用大小切分 ksud 与普通 .so
private const val EXPLOIT_BUDGET_MS = 8 * 60_000L     // :74  exploit 总预算（8 分钟）
private const val KSU_LOAD_WAIT_MS = 240_000L         // :78  注入后等 KSULOAD_END 的上限
private const val SU_VERIFY_TIMEOUT_MS = 4 * 60_000L  // :83  su 轮询上限
private const val SU_VERIFY_INTERVAL_MS = 5_000L      // :84
```

`KSU_MODULE` 环境变量（**由 payload 读取**，`src/exploit-app.c:5285`）：
```kotlin
val shellCmd = "cd $WORK_DIR && KSU_MODULE=/data/local/tmp/.ksu-skip-load " +
    "KSU_LABEL='SukiSU Ultra' ./ptyrun ./pk64560 --run 2>&1"
```
注意 `KSU_MODULE` **故意指向一个不存在的路径**（`.ksu-skip-load`）。98260 的 payload 会在 root shell 之前自己做一次 `finit_module`，但它只加载模块、不做用户空间安装（`/data/adb/ksu/bin/su` 与 `/system/bin/su` 都由 ksud 的 late-load 创建）。让它的读取失败（日志里是 `KSU_LOAD_FAIL stage=read errno=2`，不在 fatalGate 里），再把「加载 + 安装」一起交给 `ksu_load_setup.sh` 的 `late-load`，避免出现「模块在、su 永远不出现」。

> v1.2.6 之前的写法是指向真实的 `kernelsu-prague.ko`，会让 payload 自己 `finit_module` 而跳过用户空间安装；现已改成跳过路径。改这段时不要退回旧写法。

---

## 6. payload（`assets/pk64560`）说明 —— 最重要的一节

### 6.1 它是谁

- 来源：GitHub `ChaYeWuu/prague-CVE-2026-98260-OS4.0`，取 `payload/libexploit.so` **原样拷贝**。
- **不是本仓库的源码**。本仓库没有任何 C 源码能重建它。要改它只能回上游改 `src/exploit-app.c`（6365 行）重新编译。
- 格式：`ELF e_type=2`（ET_EXEC）、machine 183（AArch64）、**无 PT_INTERP**（静态链接，可直接 exec）。
- 大小 2,296,288 B，sha256 `ab4e76d2743021d66fb9b017e36b8502c22ffb95af97a1be037530ce4b77caa1`。

### 6.2 漏洞原理（你排查问题时会用到）

`begin_new_exec()` 的执行顺序是：
```
point_of_no_return = true
  → de_thread(me)                    ← leader 的 struct pid 从这里开始从 PIDTYPE_PID 桶里被清空
  → exec_mmap(bprm->mm)              ← 这里可能失败
  → if (retval) goto out
  → posix_cpu_timers_exit(me)        ← 清理在这里，太晚了
  → exit_itimers(me)
```
`de_thread()` 把 leader 的 `struct pid` 摘掉之后、`posix_cpu_timers_exit()` 之前，如果 `exec_mmap()` 失败提前返回，那么**进程级 POSIX CPU timer 的节点还挂在红黑树上，但 `k_itimer` 已经被释放了** —— 典型的 UAF。

之后任何一次 `timerqueue_del` → `rb_erase_cached` → `rb_next(node)` 都会读 `node->rb_right`，而那个位置（对象 +0x80）已经是 SLUB 的 freepointer，于是跳到垃圾地址 panic。

### 6.3 exploit 的六层（日志里的关键标记）

| 层 | 成功标记 | 失败标记 |
|---|---|---|
| 触发层（98260） | `R98260_GATE ... blocked=1 state=D` | `blocked=0 ... reason=exec_passed` |
| stage0（受控写） | `STAGE0_GATE_PASS slide=0x...` | `STAGE0_MISS_CLEAN` / `BOOTID_WRITE_MISS` |
| cross-cache | `CROSSCACHE_KS leaked=0x...` | `CROSSCACHE_...FAIL` |
| misc bridge | `MISC_BRIDGE_RW_PASS fd=...` | `BRIDGE_VALIDATE fd=-1` |
| 物理读写 | `PIPE_CANONICAL_PASS` / `PHYS_LAYOUT_PASS` | `PHYS_PROBE_FAIL stage=...` |
| cred 覆写 | `STAGE8_CHILD_USERLAND_PASS uid=0 caps=0x1ffffffffff` | `STAGE8_...FAIL` |
| KernelSU | `KSU_FINIT_MODULE result=0` | `KSU_LOAD_FAIL stage=...` |

**判断成败只看 `su -c id`。** 上面任何一层的「成功」都不代表最后能拿到 root —— 这是这个项目最容易犯的错（历史上因为只看某一层就把假绿当成功，浪费过好几天）。

### 6.4 可用参数

```
--preflight     只读体检，不改内核（安全，随便跑）
--run           完整跑一遍
--stageb        只跑触发层，不写内核（调触发器时用）
--stage0only / --bridgeonly / --pipeonly / --physonly
--probe-task    打印目标进程的 task_struct 快照
--exec-child    内部用，不要手动调
```

### 6.5 环境变量

| 变量 | 作用 | 默认 |
|---|---|---|
| `KSU_MODULE` | ko 的路径 | `/data/local/tmp/kernelsu-prague.ko` |
| `KSU_LABEL` | 日志里显示的模块名 | `KernelSU` |
| `PRAGUE_NO_SHELL` | 设 1 则 root child 不启 `sh -i`，直接 `_exit(0)` | 未设 |

> **`PRAGUE_NO_SHELL=1` 会让 root shell 消失**，之后就没办法通过它执行 `ksud late-load`。本 App **不设**这个变量。

---

## 7. 踩过的坑（按重要性排序，全部是真实踩过的）

### 坑 1：`adb push` 无 fsync，panic 后 payload 变成等长全 NUL ★★★★★

**现象**：App 点下去，`[4/5]` 之后一行 exploit 输出都没有，直接跳到「未捕获到 ROOT_CHILD_HOLD_PASS」。手工跑 `--preflight` 也零输出 rc=0。
**原因**：`adb push` 写文件没有 fsync，设备 panic 时 ext4 `data=ordered` 丢数据 → **文件大小保留、内容全 0**。
**检查**：`od -An -tx1 -N16 /data/local/tmp/ksu-root/pk64560`，如果全是 `00` 就是中招。
**修复**：`ensureFiles()` 已经加了 sha256 双校验（`assetSha256()` + `statHashes()`），大小对但哈希不对会重写。

### 坑 2：`run.log` 无 fsync，panic 后内容全 NUL ★★★★★

**现象**：`run.log` 大小正常（几 KB）但 `od -c` 从第 0 字节就是 `\0`。
**原因**：`File.appendText()` 只 `close()`，数据进 page cache 无 fsync。
**修复**：`MainActivity.appendLog()` 已改成 `FileOutputStream(..., true).use { write; flush; fd.sync() }`。**改日志相关代码时不要退回 appendText。**

### 坑 3：`reader.readLine()` 阻塞导致所有超时失效 ★★★★

**现象**：exploit 进 root child 后不再回传输出，App 界面卡住几分钟，8 分钟预算和 4 分钟 su 超时全部不生效。
**原因**：`readLine()` 阻塞时循环体里的 `System.currentTimeMillis() < deadline` 永远轮不到。
**修复**：已改成独立线程读 + `LinkedBlockingQueue` + `poll(500ms)` 轮询（`RootController.kt` 的 `runExploit`）。**不要改回同步 readLine。**

### 坑 4：`shell_su_adb_only` 导致 su 显示「找不到」★★★★★

**现象**：`su -c id` 报 `sh: su: inaccessible or not found`，但 `/system/bin/su` 明明存在；而 `adb shell` 里跑同样的命令是成功的。
**原因**：KernelSU/SukiSU 的 feature ID=6 `shell_su_adb_only` 只放行**由 adbd 或 ksu 域派生**的进程。App → Shizuku → `shizuku_server`（`ppid=1`，被 init 接管）不在放行范围内，`su` 的 exec 直接被拦。
**这是本项目最坑的一次假绿**：所有 adb 端验证都通过，只有 App 这条链会失败。
**修复**：`ksu_load_setup.sh` 里 `"$DIR/ksud" feature set shell_su_adb_only 0`。**任何「su 存在但调不通」的排查，第一件事就是查这个 feature。**

### 坑 5：`ksud` 在 APK 里的文件名会变 ★★★★

- KernelSU：`lib/arm64/libksud.so`（4,990,848 B）
- SukiSU v4.3.0-r1：`lib/arm64/libfdbqiupsrx.so`（6,102,016 B）
- SukiSU v4.3.0-r2：`lib/arm64/libtqqfthevwb.so`（6,102,016 B）
- SukiSU v4.3.0 / 41100：6,093,280 B

**不要硬编码文件名**。`resolveKsudLib()` 会先试偏好名，失败就扫 `lib/arm64/*.so` 里 ≥4MB 的那个。

### 坑 6：patch 脚本「断言失败但 BUILD OK」★★★★

**现象**：改了 C 源码，`./gradlew assembleDebug` 成功，但 sha256 和改之前一模一样 —— 说明 patch 根本没写进去。
**原因**：python patch 脚本里 `assert` 在 `open().write()` 之前抛异常，文件没写；而构建只是重新编译了旧文件。
**教训**：**每次 patch 后必须校验 sha256 变了**。不要用 `grep BUILD SUCCESSFUL` 当作「改上了」的证据。

### 坑 7：`FAKE_CPU_TIMER_STRIDE` 与 `k_itimer` 大小必须匹配 ★★★★

- prague 6.12：`struct k_itimer` = **256 B**（0x100），`FAKE_CPU_TIMER_SLOTS` = 32
- sheng 5.15：`struct k_itimer` = **264 B**（0x108），`FAKE_CPU_TIMER_SLOTS` = 31

用错 stride 会让每个槽位错位 8 字节，伪造的 `rb_right` 落到垃圾上，stage0 即使命中也只会 `ORDER1_RECLAIM_GATE changed_nodes=0`。

**怎么查**：`/proc/slabinfo` 里 `posix_timers_cache <active> <total> <objsize> <objs_per_slab> <order>` —— 第 4 列就是。

### 坑 8：`slab_drain` 的判据不能只看 total ★★★★

`active < limit && total < limit` 不够。历史上出现过 `active=726 / total=832`（87% 占用）也通过、随后 panic 的情况 —— total 掉下来只能说明 slab 页数少了，**不能说明那些页真的被 discard 过**。

真正的区分力在 `active`：
- 成功：`547/800`、`579/768`（约 68%）
- 失败：`726/832`（87%）

所以 `slab_drain_active_limit = 640` 必须保留。

### 坑 9：`ATTEMPT_DEFER` 会留下僵尸 victim，锁屏后必重启 ★★★★

**现象**：root 成功后不锁屏没事，锁屏十几分钟必重启。v1.1.7 没这个问题，v1.1.10 开始有。
**原因**：DEFER 路径故意不 reap victim，让它停在 `pause()` 上挂着 32 个悬空 timer 节点。attempt 次数从 1 涨到 6 之后，5 次 DEFER 各留一个 → 锁屏进 doze 时 OOM killer 杀掉 → `cleanup_timerqueue` 踩 freepointer → panic。
**修复**：移植了上游的 `retire_victims_safe()`（`src/exploit-prague.c`），在 DEFER / LANE_INVALID 前先 `kill` 全部 victim → `drain_timer_freelist()` 占位 → 只有 drained 才 `waitpid`。
**注意**：这个修复在 v1.1.15 里，但 **v1.2.x 换成了 KAIROS 的 payload，那段代码在 v1.2.x 里不存在**（payload 是二进制）。KAIROS 自己的实现已经包含类似逻辑。

### 坑 10：loadavg 在这台机器上完全不可信 ★★★

`/proc/loadavg` 长期 19–21，但同一时刻 `top` 是 `757% idle`、`procs_running 1`、`/proc/pressure/cpu` 全 0。是被 D 状态进程污染的。
**任何用 loadavg 判断「系统忙不忙」的代码都是错的。** 用户为此纠正过两次。

### 坑 11：`adb reconnect` 会让设备消失更久 ★★

小米设备掉线时不要用 `adb reconnect`，会彻底消失。用 `adb kill-server; adb start-server`。另外 `adb reboot` 之后小米默认 USB 模式是「仅充电」，需要手动切回「传输文件」才能恢复 adb。

### 坑 12：Android 11+ 包可见性 ★★★

`targetSdk = 34` 时，不声明 `<queries>` 就看别的包：
```xml
<queries>
    <package android:name="com.sukisu.ultra" />
    <package android:name="me.weishu.kernelsu" />
</queries>
```
不加的话 `packageManager.getPackageInfo("com.sukisu.ultra", 0)` 抛 `NameNotFoundException`，成功弹窗会误报「没找到已安装的管理器」。**但 `pm path`（走 Shizuku/shell）不受这个限制** —— 两处判断不一致时先怀疑这里。

### 坑 13：SukiSU 的主 Activity 没有 `CATEGORY_LAUNCHER` ★★

`getLaunchIntentForPackage("com.sukisu.ultra")` 返回 `null`，不能用它判断「装没装」。用 `getPackageInfo()`。打开管理器时如果 `getLaunchIntentForPackage` 为 null，退回：
```kotlin
Intent(Intent.ACTION_MAIN).setPackage(pkg).addCategory(Intent.CATEGORY_DEFAULT)
```

### 坑 14：`ShizukuHelper.exec()` 超时后读 `out` 有竞态 ★★

`tOut.join(timeoutSec * 1000)` 返回后如果读线程还在跑，紧接着 `out.toString()` 会在另一个线程还在写 `ByteArrayOutputStream` 的时候读它。**超时路径下拿到的是半截数据。**
**对策**：调用方要按「输出可能不完整」处理；`kp_arm.sh` 那个 300 秒循环配 40 秒 exec timeout 的 bug 就是这么来的（已废弃该脚本）。

### 坑 15：KAIROS 的 payload 里 `armed=0` 是假象 ★★

`R98260_LANE ... timers=0 armed=0` —— 这个字段是 fork 之后的**普通全局变量**，父子进程不共享，父进程读到的是自己那份（永远是 0）。**不要据此判断 timer 没建**。真要看有没有建，看 `blocked=1` 和 `state=D`。

### 坑 16：`ksud late-load` 与 `finit_module` 不能都做 ★★★

98260 版本的 payload **自己会 `finit_module`**（用 `KSU_MODULE` 指定的 ko）。如果之后又跑 `ksud late-load`，同名模块会 `File exists (os error 17)`。
所以 `ksu_load_setup.sh` 现在的逻辑是：先 `grep -qi kernelsu /proc/modules`，**已加载就跳过 late-load**，只在「模块没加载」时才 fallback。

### 坑 17：`/system/bin/su` 是 LKM 起来之后才 install 的，会晚几秒到几分钟 ★★★

只查一次会把成功判成失败。必须轮询（当前 4 分钟上限 / 5 秒间隔）。

### 坑 18：`su` 存在性判断不能用 `ls ... | grep` ★★

`ls /system/bin/su` 在文件不存在时输出的错误信息里也包含 `/system/bin/su`，`contains()` 恒真。要用 `[ -e /system/bin/su ] && echo SU_EXISTS || echo SU_MISSING`。

---

## 8. 日志与排查

### 8.1 日志在哪

- App 内「日志」区显示的是内存缓冲 `logBuf`，重启 App 会清空。
- 落盘在 `/data/data/com.ksuroot.app/files/run.log`，用：
  ```bash
  adb shell "run-as com.ksuroot.app cat files/run.log"
  ```
- 每次点「开启 root」都会 `writeText("")` **清空旧日志** —— 上一次失败的详情会被覆盖。**要留证据就先导出或先拷走。**

### 8.2 快速判据表

| 日志 | 含义 | 该干什么 |
|---|---|---|
| `TARGET_PROFILE_GATE_FAIL` | 指纹/机型不匹配 | 确认设备型号；不要改 gate 绕过 |
| `SLAB_BASELINE_DIRTY ... hint=reboot` | 开机后 slab 已经脏了 | 重启设备 |
| `R98260_GATE ... blocked=0 ... exec_passed` | 触发层没卡住 | 换 CPU 对 / 重试；这是概率问题 |
| `ORDER1_RECLAIM_GATE changed_nodes=0` | 跨缓存没抢到页 | 看 `SLAB_SLICE_KEEP/DROP`；检查 stride |
| `MISC_BRIDGE_RW_PASS` 之后失败 | 物理读写层 | 看 `PHYS_PROBE_FAIL stage=...` |
| `STAGE8_CHILD_USERLAND_PASS uid=0` 之后失败 | 加载模块/装 su 阶段 | 看 `KSU_LOAD_FAIL stage=...` |
| `KSU_FINIT_MODULE result=0` 但 `su` 不通 | 模块在、用户态没装好 | 查 `shell_su_adb_only`；查 `/system/bin/su` 时间戳 |
| `BRIDGE_TARGET_INVALID` | 传给 bridge 的地址不合法 | 只在偏移算错时出现；prague 正常路径不该有 |
| `BRIDGE_RESET_FAIL errno=29` | ESPIPE，拿到的是真实 uhid | **设计内分支，不是 bug**；连续多次且 `fd=-1` 说明还没抢到伪造对象（4.0.6.0 上可能要 8～43 次） |
| `PIPE_CACHE_GATE ... ok=0` → `RESULT_FAIL code=12 stage=pipe_cache_gate` | carrier 页没被 kmalloc-8k 取走，页分配时序问题；内核已复原（`dirty=0`） | **不要重启，直接再点一次** |
| `SLAB_BASELINE active=800 total=960` | 基线被上一次残留的 root child 污染 | 重启设备再跑 |

### 8.3 手工验证（不经过 App）

```bash
adb shell "cd /data/local/tmp/ksu-root && ./pk64560 --preflight"    # 只读体检
adb shell "cd /data/local/tmp/ksu-root && ./pk64560 --stageb"       # 只跑触发层
adb shell "su -c id"                                                # 最终判据
adb shell "grep -i kernelsu /proc/modules"
adb shell "getenforce"
adb shell "su -c 'ls -lZ /system/bin/su'"
adb shell "/data/local/tmp/ksu-root/ksud feature list"
```

---

## 9. 当前状态（2026-11-24）

| 项 | 状态 |
|---|---|
| 当前源码版本 | **versionCode 42 / versionName 1.2.10** |
| 最新 commit | `4e5b64e release: v1.2.9 (versionCode 41) — 迁移到 CVE-2026-98260` |
| 线上 release | **v1.2.9**，release id `450113757`，tag `v1.2.9` |
| 未提交改动 | `CHANGELOG.md` / `app/build.gradle.kts` / `app/src/main/AndroidManifest.xml`（v1.2.10 的 `<queries>` 修复） |
| 设备系统 | **OS4.0.6.0.XAACNXM**（2026-11-24 从 4.0.5.0 走 OTA 上来） |
| v1.2.10 是否装机 | 是，`versionCode=42` |

### 9.1 OS4.0.6.0 适配结论：可用，但需要重试

**内核是同一份二进制，两个版本共用。** 从 `prague_images_OS4.0.6.0.XAACNXM_20261029...tgz` 的 `boot.img` 里直接读出来的：

```text
Linux version 6.12.69-android16-6-g586bfab1b9c5-abogki536749445-4k
  (kleaf@build-host) (Android (14043575, +pgo, +bolt, +lto, +mlgo, ...))
  #1 SMP PREEMPT Mon Jul 20 18:34:35 UTC 2026
```

SCM hash `g586bfab1b9c5`、kleaf build id `abogki536749445`、编译时间 2026-07-20 —— 与 4.0.5.0 完全一致。同一个 build id 不可能对应两份二进制，所以小米没重编内核，**CVE-2026-98260 在 4.0.6.0 上依然存在**。

payload 的三个 gate 照常放行（指纹前缀是 `Redmi/prague/prague:`，不含小版本号），App 侧也没有系统版本硬编码。

> 注：4.0.5.0 的 images 包把 `boot.img` 排在 11.5 GB 的 `super.img` **之后**，所以没做逐字节 sha256 对比（要拖 14.2 GB）。4.0.6.0 的包顺序相反，`boot.img` 在最前面，只拉了 32 MB 就提取到了。

### 9.2 已真机验证可用的链路

- **2026-11-24 / OS4.0.6.0**：App 点击第二次跑通。`su -c id` = `uid=0 context=u:r:ksu:s0`、`kernelsu 536576 1 - Live (O)`、`/system/bin/su` 存在（6093280 B）、`Enforcing`、SukiSU 显示 19 个超级用户。第一次点击失败（`pipe_cache_gate`，干净退出，没重启）。
- v1.2.6（KAIROS 98260 payload + 修 `KSU_MODULE` 时序）→ 4.0.5.0 上一次跑通、零 panic。
- v1.1.7（64560 时代）也验证过，那是完全不同的 payload，别拿它的日志当参考。

### 9.3 4.0.6.0 上的失败模式（重要）

**① bridge 抢 uhid 的重试成本明显变高。** 4.0.5.0 时代 `BRIDGE_RESET_FAIL` 失败 3 次、第 4 次就抢到；4.0.6.0 实测要 8～43 次。payload 的重试上限大约 12 次，耗尽后**可能**直接 panic。

**② `pipe_cache_gate` 失败是干净的，直接重试即可。** 特征是：

```text
PIPE_CACHE_GATE ... ok=0
PIPE_CACHE_GATE_RETRY
KERNEL_STATE_RESTORE_PASS dirty=0
RESULT_FAIL code=12 stage=pipe_cache_gate restart=0
```

payload 自己会说「重新执行提权即可」。这种情况**不要重启设备**，再点一次就行 —— 实证：2026-11-24 那次就是第一次 `code=12` 干净失败、第二次直接成功。

**③ 残留的 root child 会污染下一次的基线。** 手工跑成功后台进程没退干净时，下一次 `SLAB_BASELINE` 会从 `256/256` 涨到 `800/960`，之后的尝试大概率在 `STAGE0_DIRTY_ARM` 就 panic。**跑完不留用它，就重启一次再跑。**

**④ 失败不一定 panic。** 实测 6 次里 3 次 panic、3 次干净退出。所以「重启了」这个现象不能当作「一定崩在某一层」的证据，要看 `run.log` 断在哪。

### 9.4 已知未验证 / 待办

1. ~~v1.2.10 的 `<queries>` 修复在成功弹窗上的效果~~ —— **已验证**。2026-11-24 成功那次之后 SukiSU Ultra 管理器被正常拉起并显示 `工作中 / LKM / 越狱模式 / 19 个超级用户`，说明包可见性生效、弹窗没再误报「没找到已安装的管理器」。
2. **KAIROS payload 的源码可审计性** —— 它是预编译二进制，无法逐行审。如果要长期维护，应该 fork 上游 `prague-CVE-2026-98260-OS4.0` 自己编译。
3. **6.12.69 以外的内核** —— 不支持，不要试图加 profile 硬套（`LINK_*` 全是绝对地址，换个内核就全错）。
4. **bridge 命中率下降的原因** —— 4.0.6.0 上从 4 次涨到 8～43 次，内核没变，所以是用户空间的内存/时序差异。没查到底，属于上游 payload 的范畴。
5. **`shell_su_adb_only` 在这版 ksud 上显示 `[NOT_SUPPORTED]`** —— 但 `su` 链路正常，不影响使用；`ksu_load_setup.sh` 里的 `feature set` 调用失败也无害。

### 9.5 软重启：不丢 root 的重启方式

Zygisk Next 这类模块要「重启」才装载，但普通重启会把 LKM 丢掉（临时 root 的本质）。这时用**软重启** —— 只重启 zygote / system_server，内核和已加载模块都不动：

```bash
su -c "setprop ctl.restart zygote"
```

结果：所有 App 被重新拉起、Zygisk 装载完成，而 `kernelsu` 仍在 `/proc/modules` 里、`su` 继续可用。前提是当前已经拿到 root。

> 交付给最终用户时可以把这条写进「常见问题」：装完 Zygisk 类模块提示需要重启时，**不要真的重启手机**，用软重启。

---

## 10. 禁忌（不要做的事）

1. **不要把 `assets/pk64560` 换成别的机型的版本**。它是 6.12.69 专用，换到 5.15 上会 `TARGET_PROFILE_GATE_FAIL`（这是好事，至少不写内核）；如果强改 gate 会写坏内核内存。
2. **不要用 `loadavg` 判断能不能跑**。见坑 10。
3. **不要在 `run.log` 里找上一次失败的记录**。每次点击都会清空。
4. **不要相信任何中间层的「PASS」**。唯一有效判据是 `su -c id`。
5. **不要改 `slab_drain_active_limit` 为 0**。那会把 87% 占用的情况放进来，直接 panic。
6. **不要在没有备份的情况下改 `app/src/main/assets/*`**。这些是二进制，改坏了没法从源码重建。
7. **不要让 `ksud late-load` 和 payload 自带的 `finit_module` 同时跑**。见坑 16。
8. **不要用 `adb reconnect`**。见坑 11。
9. **不要留着上一次的 root child 就再跑一次**。它挂着的 timer 会把 `SLAB_BASELINE` 从 `256/256` 污染到 `800/960`，下一次多半在 `STAGE0_DIRTY_ARM` 就 panic。不用了就重启。
10. **不要把 `pipe_cache_gate` 的失败当成「必须重启」**。它是干净失败（`dirty=0`），再点一次即可；乱重启反而丢掉干净的基线。

---

## 11. 上游与许可证

| 项 | 值 |
|---|---|
| payload 上游 | [`ChaYeWuu/prague-CVE-2026-98260-OS4.0`](https://github.com/ChaYeWuu/prague-CVE-2026-98260-OS4.0) |
| 上一代（v1.0.x–v1.1.x） | `ChaYeWuu/prague-CVE-2026-64560-OS4.0` |
| 本仓库 | `wanwuzhishi/ksu-root`，Apache-2.0 |
| CVE-2026-98260 | `exec: Cleanup POSIX timers right after de_thread()`，影响 ≤ 7.3-rc3，CWE-825 / CWE-416，CVSS 3.1 = 7.8 |
| CVE-2026-64560 | `posix-cpu-timers: Prevent UAF caused by non-leader exec() race`，CWE-362，CVSS 3.1 = 7.8 |

上游修复 commit：`920f893f735e92ba3a1cd9256899a186b161928d`（64560）、98260 对应的 commit 在 `linux-cve-announce` 2026-10 的公告里。

---

## 12. 快速自检清单（你接手后建议先跑一遍）

```bash
# 1. 设备在不在
adb devices -l

# 2. 当前 root 状态
adb shell "su -c id" 2>&1
adb shell "grep -i kernelsu /proc/modules" 2>&1

# 3. 工作目录里的东西对不对
adb shell "ls -l /data/local/tmp/ksu-root"
adb shell "sha256sum /data/local/tmp/ksu-root/pk64560"

# 4. payload 能不能跑（只读，安全）
adb shell "cd /data/local/tmp/ksu-root && ./pk64560 --preflight"

# 5. App 版本
adb shell dumpsys package com.ksuroot.app | grep -E "versionCode|versionName"

# 6. 本地代码状态
git log --oneline -3 && git status --short
```

如果第 4 步输出全空但 rc=0，**先看 `od -An -tx1 -N16` 是不是全 0**（坑 1）。
