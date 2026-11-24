# Changelog

## [1.2.10] - 2026-11-18

### 修成功弹窗误报「没找到已安装的 KernelSU / SukiSU 管理器」

- 现象：`su` 明明已经可用，成功弹窗底部却还是写着「没找到已安装的 KernelSU / SukiSU 管理器。」
- 根因：`targetSdk = 34` 时 Android 11+ 的**包可见性**生效。App 没有在 `AndroidManifest.xml` 里声明 `<queries>`，于是 `packageManager.getPackageInfo("com.sukisu.ultra", 0)` 会抛 `NameNotFoundException` —— 即使该包确实装着。同一个包用 `pm path`（走 Shizuku/shell 权限）就能查到，所以 `[2/5]` 里 ksud 提取一切正常，只有弹窗这一处判断失败。
- 修复：`AndroidManifest.xml` 加
  ```xml
  <queries>
      <package android:name="com.sukisu.ultra" />
      <package android:name="me.weishu.kernelsu" />
  </queries>
  ```
- 影响面：只有成功弹窗的「打开管理器」按钮与那段提示；root 流程本身不受影响。

## [1.2.9] - 2026-11-18

### 状态卡片不再显示 loadavg

- 「系统: 负载 19.9」这行删掉了，改成「系统: 已开机 25 分钟」。
- 原因：本机 `/proc/loadavg` 长期 19–21，但同一时刻 `top` 是 757% idle、`procs_running=1`、`/proc/pressure/cpu` 全 0 —— 是被 D 状态污染的读数，显示它只会让用户误以为"系统很忙"。
- `refresh()` 里 `load1 > 8f` 那条「建议等系统闲下来再开」也一并删掉，只在 `uptime < 120` 时提示「刚启动，建议等两分钟再开」。
- 关于页主描述里的「运行 CVE-2026-64560 内核提权链」改成 `CVE-2026-98260`，耗时从「3–8 分钟」改成「2–5 分钟」。

## [1.2.8] - 2026-11-18

### 文案全面刷新

- **软件内「关于」页**：漏洞从 CVE-2026-64560 改为 CVE-2026-98260，补了漏洞机制说明；删掉「约 40% 概率 panic（实测 17 次里 7 次）」与「系统负载高时失败率更高」这两条过时/错误的说法；实测记录补到 2026-11-18（v1.2.6 一次跑通）；致谢加上 98260 上游。
- **README**：新增「漏洞：CVE-2026-98260」一节；「这是什么」的对比表去掉 kprobe 布防那一行；「工作原理」按 98260 的八步重写；「实测记录」按 CVE 分成 98260 / 64560 两张表；「已知限制」删掉 loadavg 相关的错误建议，改为「loadavg 在本机不可信」；补上「`ksud late-load` 不能拆开」这个 1.2.6 踩过的坑。
- **AI-GUIDE.md**：3.2 节从「检查系统负载」改成「检查开机时长（不要看 loadavg）」；判据表删掉「布防 kprobe」行；`su` 文件大小示例更新为 4990848 / 6093280；命令速查去掉 `kp_arm.sh`。
- **NOTICE**：payload 来源改为 `prague-CVE-2026-98260-OS4.0`；上游漏洞章节改为 CVE-2026-98260 的修复说明。

## [1.2.7] - 2026-11-18

### 修复成功弹窗误报「没找到管理器」

- `MainActivity.currentManagerPkg()` 原来用 `getLaunchIntentForPackage() != null` 判断「装没装」。SukiSU 的主 Activity（`com.sukisu.ultra/.ui.qmhhdkjpvkqActivity`）只声明了 `CATEGORY_DEFAULT`、没有 `CATEGORY_LAUNCHER`，这个方法返回 null，于是**刚从这个管理器提完 ksud，弹窗却说「没找到已安装的 KernelSU / SukiSU 管理器」**。
- 改用 `getPackageInfo(pkg, 0)` 判断安装；「打开管理器」按钮加 fallback：拿不到 launch intent 时退回 `ACTION_MAIN + CATEGORY_DEFAULT`。

## [1.2.6] - 2026-11-18

### 修复「模块加载了但 su 永远不出现」

- 98260 的 payload 在 root shell 之前自己 `finit_module` 把模块塞进内核，但它**只做 finit_module**，不创建 `/data/adb/ksu/bin/su` 与 `/system/bin/su`——这两个是 `ksud late-load` 的用户空间安装部分做的。
- 而 `ksu_load_setup.sh` 看到 `/proc/modules` 里有 `kernelsu` 就跳过 `late-load`，两边各做一半，结果模块在、`su` 永远不存在。
- 修法：`KSU_MODULE` 指向一个不存在的路径，让 payload 的读取失败（`KSU_LOAD_FAIL stage=read`，不在 fatalGate 里），模块完全交给 `ksu_load_setup.sh` 的 `late-load` 一次做完「加载 + 安装」；脚本的短路条件也改成「模块在 **且** `su` 在」才算就绪。
- 2026-11-18 真机验证：一次跑通，`su -c id` 返回 `uid=0 context=u:r:ksu:s0`。

## [1.2.5] - 2026-11-18

### 移除平板（sheng）支持，只保留 prague

- 删除 `app/src/main/assets/pk64560-sheng`（141,144 B）与 `ASSETS` 里对应的条目。
- 删除 `runExploit()` 里按 `getprop ro.product.device` 选 payload 的分支，固定用 `./pk64560`。
- `chmod 755` 恢复成只处理 `pk64560` / `ptyrun` / 两个脚本。

**原因**：CVE-2026-98260 在 5.15.194 上不成立 —— 它依赖 6.12 的 `mmap_read_lock_killable`
制造 `exec_mmap()` 失败，而 5.15 的 `exec_mmap()` 用的是不可中断的 `mmap_read_lock()`，
`exec_update_lock` 也没有外部竞争者，触发条件造不出来。平板改用 GhostLock（CVE-2026-43499）。

### 影响面
- 手机（prague）路径零改动：`pk64560` 仍是 2,296,288 B / `ab4e76d2...`，与 v1.2.0 一致。
- APK 体积减少约 141 KB。

## [1.2.2] - 2026-11-18

### 双设备支持
- 同一个 APK 现在同时带两份 payload：
  - `pk64560` — prague（Redmi K90 Max，6.12.69），2,296,288 B
  - `pk64560-sheng` — sheng（Xiaomi Pad 6S Pro，5.15.194），137,560 B
- 运行时按 `getprop ro.product.device` 选：`sheng` → `pk64560-sheng`，其余 → `pk64560`。两个设备互不影响。

### sheng 的 slab drain 参数
- `slab_drain_target` 0 → 3000：5.15 的 `posix_timers_cache` 有 per-CPU `cpu_partial` 保留，跑满 32 轮后 `total` 稳定在 5700~7100，1024 的硬墙永远过不去。
- `attempts` 32 → 8、`rcu_flush_timers` 12000 → 8000：减少每轮净增的 slab 页，把 `total` 压在 3000 以内。

## [1.2.1] - 2026-11-18

### 适配 Xiaomi Pad 6S Pro（sheng / 5.15.194-android13-8）

- payload 换成按 sheng 5.15.194 重新编译的 98260 版本（137,456 B，sha256 `cb99fee5781521e797ddc8af7185f707098aca0fed4e6db294e71a5e89a0a5d8`）。
- 替换 20 个 `LINK_*` 符号地址（`_text` = `0xffffffc008000000`）与 24 个 `DP_*` 结构偏移（`task_struct` 0x1200 / `cred` 0xb0 / `vmemmap` `0xfffffffe00000000`）。
- 5.15 没有 `page->page_type`，slab 判定改为 `page->flags & (1 << PG_slab=9)`；`module_direct_base`/`module_plt_base` 校验删除。
- 新增 `STANDALONE_PROFILE_SHENG`：指纹 `Xiaomi/sheng/sheng:`，内核串 `5.15.194-android13-8-00019-gf4321180a397-ab15212794`；`race_cpu_limit=2`/`race_cpu_base=0`（sheng 的 cpu 5/6 `sched_setaffinity` 返回 EINVAL）；`timer_goal=41000`。

### 未验证

- sheng 上 `--stageb` 8 轮均为 `blocked=1 state=D`，但 5.15 的 `exec_mmap` 使用不可中断的 `mmap_read_lock`（6.12 是 `mmap_read_lock_killable`），触发层是否真实命中尚未确认。
- prague 分支未改动。

## [1.2.0] - 2026-11-08

### 换漏洞：CVE-2026-64560 → CVE-2026-98260

触发层整个替换，内核任意读写 / cred 提权 / KernelSU 加载这些下游链路保持同源。

**为什么换**：

| | CVE-2026-64560（旧） | CVE-2026-98260（新） |
|---|---|---|
| 竞争窗口 | 14 条指令 ≈ 10ns | 由父进程 `wait4()` 决定，可无限长 |
| kprobe 扰动 | 必须有，否则几乎打不中 | **完全不需要** |
| tracefs / readtracefs | 需要 | 不需要 |
| 每轮失败判据 | 无（只能看结果） | `R98260_GATE blocked=N` |

旧漏洞的窗口窄到必须靠 kprobe 在同一位置插一条探针把时间拉开（实测无扰动 21 次全 miss），
而探针会被 miuibpf 周期性抢占、需要 `kp_guard.sh` 每秒守护、每次重启都要重做。
新漏洞的窗口是「B 变僵尸后父进程先不回收」——只要我们别急着 `wait4`，节点就一直挂着，
窗口长度由我们自己控制。所以这条路上根本不需要 kprobe。

**具体改动**：
- `assets/pk64560` 换成 98260 版 payload（2,296,288 B，静态链接 ET_EXEC，无 PT_INTERP）。
- 新增 `assets/sukisu-prague.ko`（646,392 B，uapi=6）。98260 版 payload 自己 `finit_module`，
  模块路径通过 `KSU_MODULE` 环境变量传入；部署阶段会把它复制到
  `/data/local/tmp/kernelsu-prague.ko`（旧的上游 uapi=2 模块会被备份成 `.old`）。
- `[3/5]` 不再布防 kprobe，只确认 `/proc/modules` 里没有残留模块；`kp_arm.sh` 保留在包内供回退。
- `ksu_load_setup.sh`：`late-load` 改成「已加载则跳过，未加载才 fallback」——模块通常已经被
  exploit 加载好了，重复 late-load 会撞 `File exists`。
- root shell 就绪判据同时认 64560 的 `ROOT_CHILD_HOLD_PASS` 和 98260 的
  `interactive root child`。

**保留不变**：Shizuku 授权检查、payload sha256 内容校验、非阻塞读 + su 探测、
`slab_drain_active_limit`、`retire_victims_safe`（锁屏重启修复）、成功对话框、su 进度弹窗、
`shell_su_adb_only` 关闭。

### 已验证
- 编译通过，`--selftest` 由 payload 自己提供（本次没有）。
- 未验证：真机完整跑通。这是换漏洞，不是小改，第一次跑可能 panic。

## [1.1.15] - 2026-11-08

### 修复：锁屏十几分钟后必然重启

**现象**：v1.1.10 之后，root 成功后只要锁屏等待十几分钟就必然重启；不锁屏不重启。v1.1.7 没有这个问题。

**根因**：`ATTEMPT_DEFER` 的轮次会把 victim 留在 `pause()` 里不 reap（源码注释写的是「允许存活」）。这些 victim 的 32 个 `k_itimer` 已经被 `exit_itimers()` 释放（`posix_cpu_timer_del()` 里 `pid_task()` 返回 NULL → 直接 `goto out`，一次 disarm 都没做），但 timerqueue 节点还挂在它们的 rbtree 上。

v1.1.7 一次跑通（attempt=1 命中），没有残留 victim，所以不重启；v1.1.10 把 `slab_drain_target` 从 1280 收回 1024 之后，每轮要跑 6 次 attempt，其中 4~5 次 DEFER 各留一个 victim 挂着 32 个悬空节点。锁屏后系统回收内存，OOM killer 杀掉这些 victim ⇒ `cleanup_timerqueue()` 对已释放节点做 `rb_erase` ⇒ `rb_next` 读 `node->rb_right`，而对象 `+0x80` 正是 SLUB 给这个 cache 放的 freepointer（`swab(ptr) ^ random`）⇒ 踩空 panic。

**修法**（移植自 CVE-2026-98260 项目 KAIROS-KSU 的 `retire_victims_safe`）：失败轮次在 `ATTEMPT_DEFER` 之前先做安全收尸 ——

1. `kill` 全部 victim；
2. `drain_timer_freelist()`：用 `SIGEV_NONE` 定时器（`syscall(SYS_timer_create, CLOCK_MONOTONIC, ...)`，永不入树、`kmem_cache_zalloc` 走 zeroed 路径把 `+0x80` 清零）持续把 cache 的空闲槽抓走。每轮只占 `total - active` 个，绝不多占（多占会让 SLUB 新开 slab，判据永远不成立）；
3. 连续 `RETIRE_STABLE_SAMPLES`（40 × 25ms = 1s）一个都没抓到，才认为 RCU 队列已排空；
4. 只有 drained 才 `waitpid`。`RETIRE_ABORT` 时**不 reap**，留一个活着的 victim 等下一轮，比踩 freepointer panic 好。

判据不是 `active == total`：上游实测 `672/672` 时只攥住 147 个，另外 525 个是遗留和 RCU 在途对象——「全部已分配」和「全部被我们占住」是两回事。

### 新增日志
- `TIMER_HOLD stage=%s held=%d active=%d total=%d gained=%d`（只在真抓到对象时打）
- `RETIRE_GATE stage=%s active=%d total=%d held=%d rounds=%d stable=%d drained=%d`
- `RETIRE_ABORT stage=%s survivors=%d reason=freelist_unstable ...`

### 已验证
- 编译通过（`-Werror` 零警告，`-Wshadow -Wextra` 全开）。
- 未验证：真机完整 `--run` + 锁屏 15 分钟。

## [1.1.14] - 2026-11-08

### 修复：root 成功后锁屏十几分钟必重启（v1.1.10 引入的回归）

用户实测：v1.1.7 没有这个问题，v1.1.10 之后有；不锁屏不重启，锁屏十几分钟必重启。

差异在 drain 阈值：

| 版本 | slab_drain_target | active_limit | 实测 attempt |
|---|---|---|---|
| v1.1.7 | 1280 | 无 | 1 次（一次跑通） |
| v1.1.10+ | 1024 | 640 | 6 次（4 次 NOT_DRAINED 重试） |

v1.1.10 把 `slab_drain_target` 从 1280 收回到 1024 是为了治 v1.1.9 的 panic，
但副作用是 `864/896/992` 这些「active 已经很低」的轮次也被 total 判据拒掉，attempt
次数从 1 涨到 6。每多跑一轮就多一轮 56000 个 race timer + 72000 次 `RCU_FORCE`
的创建删除，失败的轮次里被 UAF 碰过的 slab 页不会立刻归还，锁屏进 doze 集中回收
slab 时就崩。

两处改动：

1. `slab_drain_target` 回退到 **1280**（v1.1.7 的取值），`active_limit` 保留 **640**
   —— active 才是 v1.1.9 那次 panic 的真正区分力（成功 547/800、579/768；崩掉
   726/832），total 放宽不影响这个防护，但能少跑几轮。
2. root 成功后、加载 KernelSU 模块前加 `slab_settle_wait("post_root", 512, 12000)`，
   最多等 12 秒让 `posix_timers_cache` 回落。等不到也继续，不阻断。

预期：attempt 次数回到 1-2 次量级，锁屏后的延迟崩溃概率显著下降。

### 已验证
- 编译零警告。
- **未做真机验证**。

## [1.1.13] - 2026-11-07

### 修复：注入 ksud 之后界面长时间没有任何反应

现象：日志打在 `--- su path check ---` 之后就不动了，界面完全没反馈，好几分钟后
才一次性回传并弹出「root 已就绪」。

根因是 `runExploit()` 用 `reader.readLine()` 直接读 pty，而它是**阻塞**的：exploit
进入 root child 之后 pty 不再实时回传，`readLine()` 就一直挂在那里，循环体里所有
的时间检查（240 秒上限、进度提示）都永远轮不到。`ksu_load_setup.sh` 里那段
`su` 等待循环本身还要跑 40×3 = 120 秒，加起来就是「几分钟没动静」。

改成独立线程读、主线程 500ms 一片地轮询：

- 流里没数据时也能按时做超时判断，不再依赖「有新行输出」才检查；
- 每 3 秒探一次 `su -c id`，一可用立刻收工——不用干等脚本那 120 秒；
- 每 15 秒打一行 `>>> 仍在等 KernelSU 生效（已 Xs，上限 240s）`，日志里有动静；
- 流结束用 `EOF_MARKER` 哨兵表示，与「暂时没数据」区分开。

配套的 UI 改动：注入 ksud、等待进度提示、`[5/5]` 轮询这三处都会弹出进度对话框
（内部加了幂等保护，不会重复弹），对话框每 500ms 更新已等待秒数。

### 说明

`shell_su_adb_only` 在日志里显示 `[ENABLED (1)]` 是**正常的**——那一刻脚本还没跑到
第 45 行的 `feature set shell_su_adb_only 0`。最终 `su -c id` 能返回 `uid=0` 就说明
脚本跑完了，只是输出没实时回传。

### 已验证
- 编译零错误。
- **未做真机验证**（设备当时不在线）。

## [1.1.12] - 2026-11-07

### 修复：ELF 边界检查可被 uint64 加法回绕绕过（严重）

`ksu_patch_module()` 校验 `.ko` 内部偏移用的是 `a + b > size` 形式：

```c
header->e_shoff + (size_t)header->e_shnum * sizeof(Elf64_Shdr) > size
symtab->sh_offset + symtab->sh_size > size
strtab->sh_offset + strtab->sh_size > size
```

`e_shoff` / `sh_offset` 都是 ELF 头里的 64 位字段，畸形模块让两个大值相加回绕成
小值就能通过检查，随后 `sections` / `symbols` / `names` 全部落在 `image` 之外，
写符号时越界写 `st_value` / `st_shndx`。改成减法形式（先判 `offset > size`，
再判 `size - offset`）：

```c
header->e_shoff > size ||
    (size_t)header->e_shnum > (size - (size_t)header->e_shoff) / sizeof(Elf64_Shdr)
symtab->sh_offset > size || symtab->sh_size > size - symtab->sh_offset
strtab->sh_offset > size || strtab->sh_size > size - strtab->sh_offset
```

正常模块不受影响（`ksu_symbols.inc` 里 218 个符号全部通过）。

### 修复：victim 就绪等待没有超时，可能永久空转

`run_race()` 里 `while (!atomic_load(&shared->victim_ready)) cpu_relax();`。
`victim_process()` 是 fork 出来的子进程，有六条 `_exit()` 提前退出路径（pin CPU
失败、`/dev/null` 打不开、`pthread_create` 失败、`sched_getcpu()` 不是 7 等），
任何一条都不会置 `victim_ready`。子进程退出不影响父进程，于是父进程会一直
100% CPU 空转到天荒地老，同时内核状态可能已被污染、又永远走不到
`terminate_and_reap` 的清理。加 5 秒 deadline，超时 `kill(SIGKILL)` + `waitpid`
后返回失败。

### 修复：`setsockopt(SO_SNDBUF)` 失败被静默忽略

spray 阶段的 socket 缓冲区设置失败时原来继续跑。注释自己就写着「sndbuf 太小会让
af_unix 把 send(8192) 切碎，拿不到 order-1 页」——失败后只会在很远的地方以
`ORDER1_STATE_UNKNOWN` 失败，白烧一轮还看不出根因。改成当场 close socket、回滚
已建的 pairs、`free(pairs)` 后 `die()`。

### 清理

- `find_task_stable()` 的 `-2` 早退没有 free `visited`（`calloc(65536, 16)` ≈ 1MB）。
- `bridge_read_once()` / `bridge_write_once()` 里各有一处与 `kernel_target_valid()`
  首句完全重复的长度检查（死代码）。
- `read_boot_id()` 的 `length >= 64` 恒假（`read` 上限就是 63），改成 `length > 63`。
- `struct rcu_flush_state` 的 `timer_ids` 字段从未赋值也从未读取，删除。
- `verify_real_uhid()` 在 `main` 里连着调了两次，去掉多余的一次。
- `slab_drain_active_limit` 上方的注释还停留在 5.15 版的「681/800 通过」，与 640
  的实际阈值矛盾，改写成 v1.1.10 的真机对照数据。

### 审查记录

这一版是三个人分工静态审查 6847 行 C + 1651 行 Kotlin 的结果。已经排除的疑点：
`parse_boot_id` 边界、`build_misc_fragments` 最大偏移 0x1F98 < 0x2000、16 处
`malloc/calloc/realloc` 全部有 NULL 检查、188 个 static 函数无死函数、
`-Wshadow -Wconversion` 零警告、pipe 物理读写跨页分块、`restore_misc_list` 的
`seen[4096]` 边界、`collect_exec_acks` 边界。

### 未修（记录在案）

- `timerfd_settime` 失败后仍计入 `irq_armed`——要改 arm 协议，风险大于收益。
- `pthread_attr_setstacksize` / `dup2` / `pthread_detach` 返回值未检查——失败概率极低。
- `irq_arm_after_deleted` 分支实际不可达（所有 profile 都设 -1），是死代码但保留。
- `marker_result` 只写不读——但 `probe_hit_now()` 末尾是 `*out = result;` 不判 NULL，
  要先给它加检查才能删。
- `run_stage8_root_probe` 的十处 `return -2` 绕过 `munmap` / `prctl`——调用方都走
  `hold_cred_unknown()` 且 noreturn，当前无可观测后果。

### 已验证
- 编译零警告（`-Wall -Wextra -Wshadow -Werror`），`--selftest` 9 用例过。
- **未做真机验证**（设备当时不在线）。

## [1.1.11] - 2026-11-07

### 新增：验证 su 阶段的进度弹窗

exploit 跑完后 App 还要轮询 `su -c id` 最多 4 分钟（`SU_VERIFY_TIMEOUT_MS`）。
这段之前完全没有界面反馈——只在 `waitedMs == 0L` 时打一句「su 还没就绪」，
之后就一直静默。用户看到 `[5/5] 验证 su ...` 长时间不动，很容易误判成卡死
或者失败。

现在进入 `[5/5]` 时弹一个不可取消的对话框：

- 标题「等待 root 生效」
- 水平进度条，以 240 秒（`SU_VERIFY_TIMEOUT_MS / 1000`）为满刻度
- 正文实时显示「已等待 N 秒（最多 240 秒）」，并说明「exploit 已经跑完，
  这一步只是在等 KernelSU 把 su 装好，通常几秒内完成」

关闭时机：日志出现 `=== ` 开头（成功/失败）、`su 就绪`、或 `不可用，请重启`；
`doEnable` 协程收尾时再兜底 dismiss 一次。

### 已验证
- `./gradlew compileDebugKotlin` 通过。
- 未验证：真机上弹窗的实际观感（进度条节奏、文案是否够清楚）。

## [1.1.10] - 2026-11-07

### 修复：drain 只看 total，slab 没 discard 也可能通过

两次 panic 都发生在 `SPRAY_DONE` 之后、`BOOTID_WRITE` 之前，而 drain 的判据
`active < limit && total < limit` 都通过了：

```
SLAB_DRAIN_PASS attempt=1 active=726 total=832 baseline=256/256 wait_ms=10454   ← panic
SLAB_DRAIN_PASS attempt=1 active=575 total=864 baseline=256/256 wait_ms=2604    ← v1.1.7 成功
```

`total` 掉到 832 只能说明 slab 页数少了，**不能说明那些页真的被 discard 过**。
`active=726 / total=832` 意味着 87% 的槽位还占着——这时伪造页很可能仍留在
allocator 手里，`node->rb_right` 是 SLUB freepointer 而不是我们写的 0，reap
走到 `rb_next` 就踩空。

新增 `slab_drain_active_limit`：判据多一条 `active < 640`。prague profile 同时把
`slab_drain_target` 从 1280 收回 1024（1280 是之前为了让 drain 别一直超时临时放宽的，
副作用是允许了这种「total 低但 active 高」的状态通过）。

代价是 drain 更容易 `RCU_NOT_DRAINED` → `ATTEMPT_DEFER` 重试，但那只是多跑一轮，
比 panic 好。

### 已验证
- 编译通过、`--selftest` 9 用例过。
- 未验证：完整 `--run`。

## [1.1.9] - 2026-11-07

### 修复：`grow_reclaim` 撞 pipe 页配额后整轮作废

上一次失败停在：

```
PIPE_PAGE_STATE tag=after_release ... page_type=0xffffffff order_flags=0x4000000000000000
PIPE_PAGE_STATE tag=grow_0..grow_7 page_type=0xf5000000 order_flags=0x4000000000000003
PIPE_PREP_FAIL stage=grow_reclaim index=72 errno=1
```

`grow_reclaim` 是把 80 个 reclaim pipe 的 ring 从 8KB 扩到 512KB
（`fcntl(F_SETPIPE_SZ, 0x80000)`）。第 72 个返回 EPERM(1)。

**根因是 `pipe-user-pages-soft`（默认 16384 pages = 64MB，按 uid 记账）**：
40 个 drain pipe + 已扩容的 reclaim pipe 都占着页，而 Shizuku 的
`shizuku_server` 也是 shell uid（2000），它手上的 pipe 一起算在同一个
user_struct 里。实测单独建 120 个 512KB pipe 能跑到 15360 pages 全部成功，
但在 exploit 里跑到第 72 个就 EPERM —— 差的就是 shizuku 那部分。

配额是动态的（对方释放就回落），所以改成**退避重试 6 次 × 300ms**，
而不是一次失败就把整轮作废。

### 已验证
- 单独跑 120 个 `pipe2` + `F_SETPIPE_SZ(0x80000)` 在设备上全成功，
  `total_pages=15360`，确认 16384 的软上限本身够用。
- 未验证：带退避重试的完整 `--run`。

## [1.1.8] - 2026-11-06

### 修复：root 成功之后 App 没有任何反应

v1.1.7 在 prague 上第一次跑通（零 panic、一次命中），`su -c id` 已经能返回
`uid=0 context=u:r:ksu:s0`，但 App 界面一直停在 `[4/5]` 转圈，既没有
`=== 成功 ===` 也没有提示。

**原因**：`runExploit()` 的读循环只有两个退出条件——读到 `KSULOAD_END`，或
`EXPLOIT_BUDGET_MS`（8 分钟）到期。而实际 `run.log` 停在 `--- su path check ---`
就再没有新行，设备上 `feature list` 却已经是
`[DISABLED] shell_su_adb_only`，说明脚本跑完了、只是 pty 不再回传后续输出
（root child 常驻时 `ptyrun` 的转发停在这里）。于是 App 白等 8 分钟。

**改动**：注入 `ksu_load_setup.sh` 时记下时刻，之后最多等 `KSU_LOAD_WAIT_MS`
（4 分钟）没有 `KSULOAD_END` 就主动跳出循环，打一行
`>>> 等 ksud 加载超时（240s），不再等 KSULOAD_END，直接验证 su`，继续走
`[5/5]`。su 的判定本来就是独立做的（`su -c id`），不依赖脚本输出。

### 新增：root 成功后的明确提示

成功时弹对话框「root 已就绪」，说明这是临时 root、重启失效，并提供
「打开管理器」按钮跳到当前生效的 KernelSU / SukiSU（优先用户手动选的那个）。
之前成功后除了日志里一行 `=== 成功 ===` 之外没有任何收尾，而 KernelSU 自己
会在加载模块后弹通知，SukiSU 不会，所以换到 SukiSU 分支后感觉「没反应」。

### 已验证
- 编译通过，`KSU_LOAD_WAIT_MS` 与成功对话框逻辑未在真机上触发验证（v1.1.7
  的那次成功是改代码之前跑的，当时 App 确实卡在等待里）。

## [1.1.7] - 2026-11-06

### 修复：payload 被 panic 写坏后 App 仍然认为「已就绪」

上一版（v1.1.6）点「开启 root」后 `[4/5]` 一行 exploit 输出都没有，直接跳到
「未捕获到 ROOT_CHILD_HOLD_PASS」。手工排查发现设备上的
`/data/local/tmp/ksu-root/pk64560` 前 64 字节全是 `00`：

```
大小 135328 B（正确）
sha256 f3a91e45…（正确值应该是 84d46564…）
od -An -tx1 -N16 → 00 00 00 00 …
```

**机理**：`adb push` / `writeViaStdin` 写文件后没有 fsync，数据留在 page cache。
内核 panic 时 ext4 `data=ordered` 只保证元数据，文件大小保留但数据块变成 0。
这和 v1.1.6 修的 `run.log` 是同一个问题——只不过这次坏的是**载荷本身**。

`ensureFiles()` 之前只比文件大小，等长的全 NUL 文件会被判成「已就绪」，
于是 `execve` 一个全 0 的 ELF 直接 `ENOEXEC`，`ptyrun` 也就一句都不输出。

**改动**：`ensureFiles()` 增加 sha256 内容校验——新增 `assetSha256()` 算本地
asset 的哈希，`statHashes()` 一次 exec 取回设备端全部 asset 的 sha256；
就绪判据改成「大小一致 **且** 哈希一致」，重试循环里的校验也一并改掉。
大小对但哈希不对时会打印 `大小对但内容已损坏（sha256 …），重写`。

### 修复：删掉基于 loadavg 的「系统还在忙」提示

原提示在 uptime < 300s 或 load1 > 8 时打印「建议等到开机 5 分钟以上、负载降到
5 以下再试」。但这台机器的 `/proc/loadavg` 长期挂在 19-21，而 `top` 显示
757% idle、`procs_running=1`、`/proc/pressure/cpu` 全 0——数字被不可中断睡眠
污染，实际空闲。已多次在 load 20 左右成功跑通，这条提示每秒都在报，
只会让人以为跑不了。现在只保留 uptime < 90s 的弱提示。

### 已验证
- `ensureFiles` 的 sha256 校验逻辑在设备上跑通：4 个 asset 全部「已就绪」。
- `pk64560` 重新部署后 sha256 = `84d465649f58ffb9fa87154946b44d05a8799f16c26b4f83d0366fe87da962bd`，
  `--preflight` 全绿（`SLAB_BASELINE active=256 total=256`、`PREFLIGHT_PASS`）。
- 未验证：完整 `--run` 仍未跑通到 root（v1.1.3 的对齐修复、v1.1.5 的
  `slab_settle_wait` + 11 个 `CROSSCACHE_TRACE` 都还没拿到完整日志）。

## [1.1.6] - 2026-11-06

### 修复：panic 之后 run.log 几乎全是 NUL，日志等于没有

连续几次 panic 之后 `run.log` 里 **4361 / 4590 字节是 NUL**，只剩 App 自己补的
「检测到上次运行被设备重启打断」三行。exploit 的输出全部丢掉，每次白跑一次
都不知道崩在哪。

原因：`appendLog()` 用的是 `File.appendText()`，它只 `close()`。`close()` 把数据
交给内核 page cache 就返回，**没有 fsync**。kernel panic 时 ext4 的 `data=ordered`
并不保证这些页已经落盘（顺序只保证先写数据再写元数据，不保证及时刷），于是文件
大小对了、内容全是 0。

修复：

```kotlin
FileOutputStream(File(filesDir, "run.log"), true).use { out ->
    out.write((s + "\n").toByteArray())
    out.flush()
    out.fd.sync()
}
```

每行一次 fsync。exploit 日志总量几百行，代价是每次多零点几毫秒，换来的是
**panic 前最后一行一定在盘上** —— 这也是定位 1.1.5 那 11 个 `CROSSCACHE_TRACE`
点里到底崩在哪个的前提。

### 已验证

- `compileDebugKotlin` 通过。
- **尚未在真机 panic 后复验日志是否真的保住了**。

## [1.1.5] - 2026-11-06

### 新增：cross-cache 前等 RCU 回调泄压 + 全程分步 trace

真机连续两次 panic 都落在 stage0 成功之后的 cross-cache 段（日志停在
`CROSSCACHE_CPU_GATE` 后一句），而那一轮 `SLAB_WAIT_TICK` 的对象峰值是
**47,712**；同一构建早前成功时只有 22,400 到 27,360。`CONFIG_RCU_LAZY=y` 下
RCU 回调是延迟执行的，系统真正忙的时候积压会重得多。

`slab_drain_target` 只判「当前计数」，管不到还排在队列里没跑的回调，所以：

1. 新增 `slab_settle_wait("crosscache_entry", limit=4096, max_ms=5000)`：
   进 cross-cache 之前读一次 `posix_timers_cache`，超过 4096 就每 100ms 再看，
   最多等 5 秒。**不阻断**——等满就带着现状继续，只是把「明知还有几万个对象
   在队列里就硬上」这种最坏情形避开。成功打 `SLAB_SETTLE_PASS`，超时打
   `SLAB_SETTLE_TIMEOUT`。
2. cross-cache 全程加了 11 条 `CROSSCACHE_TRACE step=...` 分步日志
   （`pre_clones` / `collision_child` / `post_clones` / `memfds` /
   `release_collision` / `stop_pre_begin` / `stop_pre_done` / `stop_post_done` /
   `stop_spray_done` / `collision_joined` / `recover_mm_begin` / `send_payload`）。
   stdout 本来就是 `_IONBF` 无缓冲，panic 前最后一行就是崩的位置。

### 已验证

- `--selftest` 9 用例全过、`--preflight` 全绿（prague，slab 256/256）。
- **尚未跑完整 `--run`**。

## [1.1.4] - 2026-11-06

### 修复：SukiSU 升级后管理器页显示「读不到 ksud（-1 B）」

SukiSU Ultra 从 v4.3.0-r1（versionCode 41140）升到 v4.3.0-r2（41150）之后，
管理器选择页把 SukiSU 标成「读不到 ksud（-1 B）」，只剩 KernelSU 可用。

原因：`lib/arm64/` 里那个伪装成 so 的 ksud 可执行文件**名字每次构建都可能变**：

| 版本 | 文件名 | 大小 |
|---|---|---|
| 4.3.0-r1 (41140) | `libfdbqiupsrx.so` | 6,102,016 B |
| 4.3.0-r2 (41150) | `libtqqfthevwb.so` | 6,102,016 B |

代码里写死了 r1 的名字，升级后 `stat` 直接返回 -1。

**改动**：新增 `resolveKsudLib()` —— 先试首选名，失败就扫 `lib/arm64/*.so`
取体积 ≥ 4 MB 中最大的那个。同包里其它 native 库最大只有 248 KB
（`libkptools.so` 248616 B、`libwbpprvmzhvn.so` 15336 B、`libadbroot.so` 9704 B），
4 MB 这条线能干净切分，不会误选。UI 上「读不到 ksud」也拆成
「找不到 ksud 本体」和「ksud 太小」两种文案，不再把 -1 直接抛给用户。

### 已验证

- 设备实测解析结果：SukiSU → `libtqqfthevwb.so` / 6,102,016 B；
  KernelSU → `libksud.so` / 4,990,848 B，两个都对。
- 拉下 r2 的 `libtqqfthevwb.so` 校验过确实是 ksud：ELF `ET_DYN`、
  `INTERP=/system/bin/linker64`、含 `late-load` ×7 / `shell_su_adb_only` ×2 /
  `/data/adb/ksu` ×46。
- **尚未在真机上跑完整 `--run`**（v1.1.3 的 exploit 修复同样还没验证，设备电量不足）。

## [1.1.3] - 2026-11-06

### 修复：目标地址校验误伤了自己故意非对齐的物理页探针

1.1.1 引入的 `kernel_target_valid()` 里有一条「必须 8 字节对齐」的检查。当时想的是
挡住垃圾指针，却漏了自己代码里有一处**故意非对齐**的读写：

- `DP_PHYS_PROOF_OFF = 0x6ffc`，物理页读写探针从 `scratch + 0x6ffc` 读 8 字节，
  目的就是让访问跨页边界（页内偏移 0xffc）。
- 这个地址末位是 `0xc`，于是被判为非法，`bridge_read_once()` 直接返回 -1。

真机现象（prague / OS4.0.5.0，bridge 与 pipe 全部走绿之后才暴露）：

```
PHYS_LAYOUT_PASS memstart=0x80000000 kimage_voffset=0xffffffe1c7800000 image_phys=0x80000000
PHYS_SETUP_FAIL step=scratch_read64 target=0xffffff80a5416ffc errno=22
PHYS_PROBE_FAIL stage=run errno=22
```

`errno=22`（EINVAL）是上一次调用残留的，不是内核返回的——真正原因是本地被拒。
内核的 `copy_to_iter()` / `copy_from_iter()` 本来就按字节拷贝，对非对齐地址没有要求，
这条检查没有依据。

**改动**：删掉对齐检查；`--selftest` 的 `unaligned` 用例期望值由 `0`（拒绝）改为 `1`（放行）。
其余三条（空指针、`target+length` 溢出、低于 `DIRECT_MAP_BEGIN`）保留。

### 已验证

- `--selftest` 9 用例全过（prague，设备 `958DJBWGV8ONDM4P`）。
- **该版本尚未在真机上跑完整 `--run`**（设备当时电量 6%，需要充电后重试）。

### 说明

`slab_drain_target` 仍为 1280，起步门禁 `SLAB_BASELINE_TOTAL_LIMIT` 仍为 1024。
每次跑之前请确认 `/proc/slabinfo` 的 `posix_timers_cache` 是干净的 `256 256`，
否则先重启。

## [1.1.2] - 2026-11-06

### 修复：脏 slab 下重跑会 panic

真机实测（prague，OS4.0.5.0）暴露两条：

1. **`slab_drain_target` 默认 1024 太紧**。跑过一轮后设备是 `895/1216`，
   stage0 的 11 次 attempt 只有 2 次能过 drain，bridge stage 6 次只有 1 次。
   每次白等 10s，而等待期间 stage0 的受控写还挂在内核里
   （`random_table[4].data` 指着 `init_task.saved_sigmask`）。两次 panic
   （第一轮 `attempt=7 elapsed_ms=9381`、第二轮 `attempt=2 elapsed_ms=1214`）
   都发生在这种长时间重试的 drain 等待里。改为 **1280** 后实测
   stage0 6/6、bridge 1/1 全部通过。
2. **新增起步门禁 `SLAB_BASELINE_TOTAL_LIMIT = 1024`**。干净 boot 是 `256/256`，
   跑过一轮残留到 `895/1216`。带着这种规模直接再跑，启动后 3.3KB 输出处就 panic
   （日志全 NUL）。现在超过阈值直接打
   `SLAB_BASELINE_DIRTY active=... total=... limit=1024 hint=reboot` 并以 13 退出，
   不再冒险。

### 加固：`PHYS_PROBE` setup 段逐步骤诊断

原来 14 个 `||` 条件串在一起只报 `errno=1`（EPRNO 还是残留值），看不出哪一步失败。
拆成独立分支，失败打 `PHYS_SETUP_FAIL step=<名字> target=... errno=...`。

### 已验证

- `--selftest` 9/9（prague、sheng 两台）
- prague `--preflight` 全绿
- prague 干净 boot 完整跑通到 `PHYS_PROBE`：`STAGE0_GATE_PASS` →
  `MISC_BRIDGE_RW_PASS` → `PIPE_RECLAIM_PASS` → `PIPE_CANONICAL_PASS` →
  `PHYS_LAYOUT_PASS`，其中 `BRIDGE_TARGET_INVALID` 一次未触发（新校验无误伤）。
- **未验证**：prague 完整 `--run` 拿到 root（卡在 `PHYS_PROBE stage=setup`）。

## [1.1.1] - 2026-11-06

### 加固

- **bridge 读写加目标地址校验**（对照 GhostLock 的 `attack::in_direct_map()`）。exploit 建好 misc bridge 之后，每一次内核读写都走 `bridge_read_once` / `bridge_write_once`，而目标地址来自别处：链表指针、页描述符、伪造的 `rb_node` 父/子、profile 里的符号偏移。任何一处算错或读到脏数据，`pread`/`pwrite` 过去就是一次内核态 page fault，而本机 `CONFIG_PANIC_ON_OOPS=y` 会立刻重启，日志都来不及落盘。
  现在两个入口都先过 `kernel_target_valid()`：拒绝空指针、非 8 字节对齐、长度溢出，以及落在 `0xffffff8000000000`（direct map 起点）以下的目标。被拒时 `bridge_*` 返回 -1，退回各自原有的失败分支；首次拒绝打一条 `BRIDGE_TARGET_INVALID target=... length=...`。
  这是**必要非充分**条件：目标落在内核空间内、但偏移算错（用错机型 profile、少算 8 字节）的情况它发现不了——那需要知道每个目标符号的期望区间，做不到通用。
- **新增 `--selftest`**：只跑 `kernel_target_valid()` 的 9 个边界用例，不碰内核、不需要指纹匹配，任何设备上都能跑，输出 `TARGET_VALID_SELFTEST_PASS cases=9`。这层防线写反了比不加更危险，所以要能单独验。

### 已验证

- 编译零警告（`-Wall -Wextra -Werror`），产物 133,128 B / sha256 `e5b776956281e877b23c238622b3cd4616d243014d2ac99b84b1597efa4a13f2`。
- `--selftest` 在 Xiaomi Pad 6S Pro（Android 16 / 5.15.194）上跑通：`TARGET_VALID_SELFTEST_PASS cases=9`。
- 静态核对全部 78 个 bridge 调用点的目标表达式，均为 direct map / image / vmemmap 地址，不会误拒。
- **未验证**：prague 上的完整 `--run`。校验只加在 IO 入口、不改任何逻辑分支，理论上不影响原有成功路径，但仍需真机回归一次。

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
