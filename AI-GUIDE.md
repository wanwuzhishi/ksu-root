# AI Agent 操作指南

> 这份文档是写给 **AI Agent** 的，不是写给人看的。人类用户请读 [README.md](README.md)。
>
> 目标：让一个能执行 shell 命令的 AI，在**不破坏设备**的前提下，帮用户在这台手机上开一个临时 root。

---

## 0. 你要做的事

用户想在这台手机上要 root。你要做的是：

1. 确认设备和系统版本**完全匹配**（不匹配必须停止）
2. 确认 Shizuku 已运行、App 已就绪
3. 引导用户点「开启 root」（或你自己用 `input tap` 点）
4. 监控过程，处理 panic 重启
5. 验证 `su -c id` 返回 uid=0
6. 告诉用户怎么关掉

**预期耗时**：正常 3–8 分钟；panic 重启后要重来，累计可能 20 分钟。

---

## 1. 硬约束（先读完再动手）

### 必须遵守

| 约束 | 原因 |
|---|---|
| **只在指纹完全匹配时继续** | 内核地址、结构体偏移、slab 参数都是这一版系统上量出来的。版本不对会**把内核写坏**，不是优雅报错 |
| **临时 root，绝不持久化** | 不要改 boot 分区、不要改 init.rc、不要装 Magisk 式模块。重启即失效是设计目标 |
| **panic 重启是预期行为** | 约 40% 概率。不要把它当故障去"修"，等它起来重来即可 |
| **每步先只读确认，再写** | 这条链会改内核内存。任何写操作前都要有只读证据 |

### 绝对不要做

```
❌ fastboot flash / fastboot boot / fastboot oem unlock
❌ adb reboot edl / adb reboot bootloader
❌ 修改 /system、/vendor、/boot、/init*.rc
❌ rm -rf /data、/sdcard 下的任何东西
❌ 关闭 SELinux 并持久化（setenforce 0 只在利用链的短暂窗口里有意义）
❌ 在非 prague 设备上执行任何写操作
❌ 替用户决定"要不要备份"——先问
```

---

## 2. 第一阶段：只读探测（不写任何东西）

按顺序执行，**任意一项不通过就停下来告诉用户原因**。

```bash
S=<设备序列号>

# 2.1 设备在不在
adb devices -l

# 2.2 系统指纹必须逐字匹配
adb -s $S shell getprop ro.build.fingerprint
# 期望: Redmi/prague/prague:17/CP2A.260605.016/OS4.0.0.14.XAACNXM:user/release-keys

adb -s $S shell getprop ro.product.device     # 期望: prague
adb -s $S shell getprop ro.product.model      # 期望: 2604FRK1EC
adb -s $S shell uname -r
# 期望: 6.12.69-android16-6-g586bfab1b9c5-abogki536749445-4k

# 2.3 当前 root 状态（决定走哪条路）
adb -s $S shell 'su -c id 2>&1'
# 已经 uid=0 → 直接跳到第 6 步验证，别重复跑 exploit
# 报 "inaccessible or not found" → 继续

# 2.4 SELinux 必须是 Enforcing
adb -s $S shell getenforce     # 期望: Enforcing
# 如果 Permissive → 上一次 panic 的残留，先 adb reboot，等起来再继续

# 2.5 KernelSU 模块是否已加载
adb -s $S shell 'grep -i kernelsu /proc/modules'
# 空 → 正常，需要跑 exploit

# 2.6 Shizuku 是否在运行
adb -s $S shell 'ps -A -o pid,user,args 2>/dev/null | grep shizuku_server | grep -v grep'
# 无输出 → 需要启动（见第 3 节）

# 2.7 KernelSU 管理器是否安装（App 要从它里面抽 ksud）
adb -s $S shell 'pm path me.weishu.kernelsu'
# 空 → 让用户先安装
```

### 探测结论怎么写

给用户一个简短的状态表，不要罗列原始输出。例如：

```
设备      匹配 ✅ prague / OS4.0.0.14
内核      6.12.69 ✅
SELinux   Enforcing ✅
root      未开启
Shizuku   未运行 ← 需要先启动
KSU 管理器 已安装 ✅
```

---

## 3. 准备阶段

### 3.1 启动 Shizuku（如果没在运行）

> **先确认装的是哪个实现。** 官方、Shevery、Shizuku-Next、yangFenTuoZi/Shizuku、ShizukuPlus dropin 版都能用；
> Nightzuku 与 ShizukuPlus 独立版改了权限名，**不要在这些设备上继续**（App 状态行会显示 `版本不兼容`）。
> 查一下装了哪个：

```bash
for p in moe.shizuku.privileged.api com.hamondev.shevery af.shizuku.plus.api kerneldroid.nightzuku; do
  adb -s $S shell "pm path $p" 2>/dev/null | grep -q package && echo "found: $p"
done
```

**方式 A：让用户在手机上点**（推荐，最稳）

告诉用户：

> 打开 Shizuku App，点「启动」。如果是第一次，需要先开「无线调试」并点「配对」输入六位码。

**方式 B：adb 启动**

```bash
PKG=moe.shizuku.privileged.api   # 或 com.hamondev.shevery 等
BASE=$(adb -s $S shell "pm path $PKG" | tr -d '\r' | sed 's/^package://; s|/base.apk$||')
adb -s $S shell "setsid $BASE/lib/arm64/libshizuku.so --apk=$BASE/base.apk > /tmp/shiz.log 2>&1 < /dev/null &"
sleep 6
adb -s $S shell 'ps -A -o pid,user,args | grep shizuku_server | grep -v grep'
# 期望看到: <pid> shell shizuku_server
```

> **关键**：官方启动器是伪装成 `.so` 的 native 程序，**必须带 `--apk=` 参数**。
> 用 `app_process ... moe.shizuku.server.ShizukuService` 会立刻退出（EXIT=0 但无常驻进程）。
> 这条命令对 Shevery 同样有效（已实测），因为启动器实现一致。

### 3.2 确认 App 侧状态

```bash
adb -s $S shell 'am force-stop com.ksuroot.app'
adb -s $S shell 'am start -n com.ksuroot.app/.MainActivity'
sleep 4
adb -s $S exec-out screencap -p > /tmp/ksu-state.png
```

看截图，状态行应该是：

```
Shizuku: 已就绪
root: 未开启
SELinux: Enforcing
模块: 未加载
```

**如果显示 `Shizuku: 未运行` 但 server 确实在跑**：App 启动早于 server 就绪时缓存了失败状态。`force-stop` 再 `am start` 一次即可。

**如果显示 `SELinux: ERROR: process hasn't exited`**：Shizuku binder 脏了。`pkill -f shizuku_server` 后重新启动 Shizuku。

---

## 4. 执行阶段

### 4.1 触发

**方式 A：自己点（需要 `input tap` 能力）**

```bash
# 找按钮坐标
adb -s $S shell 'rm -f /sdcard/ui.xml; uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; cat /sdcard/ui.xml' \
  | grep -oE 'resource-id="com.ksuroot.app:id/(btnEnable|btnContinue)"[^>]*bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"'

# 点主按钮（坐标按上一步输出填）
adb -s $S shell 'input tap <x> <y>'

# 弹风险管理确认框，点「我已知晓风险，继续」
adb -s $S shell 'rm -f /sdcard/ui.xml; uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; cat /sdcard/ui.xml' \
  | grep -oE 'text="我已知晓风险，继续"[^>]*bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"'
adb -s $S shell 'input tap <x> <y>'
```

**方式 B：让用户点**

> 请点「开启 root」，然后在弹窗里点「我已知晓风险，继续」。跑起来后不要退出 App。

### 4.2 监控

```bash
# 每 20 秒看一次进度，直到出现成功标志或设备重启
while true; do
  UPTIME=$(adb -s $S shell 'cut -d. -f1 /proc/uptime' 2>/dev/null | tr -d '\r')
  if [ -z "$UPTIME" ]; then
    echo "[$(date +%H:%M:%S)] adb 断开（可能正在重启）"
  elif [ "$UPTIME" -lt 180 ]; then
    echo "[$(date +%H:%M:%S)] 检测到重启，uptime=${UPTIME}s ← panic 了"
    break
  fi
  PID=$(adb -s $S shell 'ps -A -o pid,args | grep "pk64560 --run" | grep -v grep' | tr -d '\r')
  echo "[$(date +%H:%M:%S)] uptime=${UPTIME}s exploit=${PID:-无}"
  sleep 20
done
```

**不要用 `boot_id` 判断重启**：`stage0` 会改写 `random_table[4].data`，跑完后 `/proc/sys/kernel/random/boot_id` 变成 `d8f06902-80ff-ffff-80e2-d6dcd6ffffff` 这类假值。只能用 `/proc/uptime`。

---

## 5. 判据表（每一步成功/失败的标志）

| 阶段 | 成功标志 | 失败标志 | 处理 |
|---|---|---|---|
| 部署载荷 | 日志 `pk64560 已就绪`、`ksud 提取成功 (5172384 bytes)` | `未找到 KernelSU 管理器` | 让用户装 KSU 管理器 |
| 布防 kprobe | `PROBE_OK`、日志 `window0=1 tracing_on=1` | `布防失败: window0=0` | 重试一次；仍失败则重启设备 |
| exploit | `ROOT_CHILD_HOLD_PASS child=... uid=0` | `STAGE0_GATE_FAIL` / 进程消失 | 重跑；连续失败两次先重启 |
| 注入 ksud | `late_rc=0`、`kernelsu ... Live` | `late_rc≠0` | 检查 `/data/local/tmp/ksu-root/ksud` 是否存在 |
| 验证 su | `uid=0(root) ... context=u:r:ksu:s0` | `su: inaccessible or not found` | 见下方故障表 |

**最终验证（必须做这一步，不要只看 App 的绿字）**：

```bash
adb -s $S shell 'su -c "id"'
# uid=0(root) gid=0(root) groups=0(root) context=u:r:ksu:s0

adb -s $S shell 'grep -i kernelsu /proc/modules'
# kernelsu 233472 1 - Live 0x0000000000000000 (O)

adb -s $S shell 'ls -l /system/bin/su'
# -rwxr-xr-x 1 root root 5172384 （KernelSU su_compat 伪造的入口）
```

---

## 6. 故障处理

### 6.1 panic 重启了

**这不是错误。** 等设备起来（40–60 s），然后：

```bash
adb wait-for-device
sleep 20
adb -s $S shell getenforce       # 确认 Enforcing
adb -s $S shell 'su -c id 2>&1'  # 应该报 not found
```

重开 App → 主按钮会变成 **继续上次（已中断）** → 让用户点它（或你点）。

### 6.2 panic 后 SELinux 是 Permissive

再跑会失败在 `STAGE8_ENV_GATE uid=2000 ... enforcing=0`。

```bash
adb -s $S shell getenforce    # Permissive
adb reboot && adb wait-for-device && sleep 25
adb -s $S shell getenforce    # 应该回到 Enforcing
```

### 6.3 `su` 不存在但模块已加载

```bash
adb -s $S shell 'ls -l /data/local/tmp/ksu-root/ksud'
# 存在？ → 说明 ksud late-load 没跑成，重跑 exploit
# 不存在？ → 让 App 重新部署载荷
```

### 6.4 App 显示 `Shizuku: 未运行` 但进程在

App 缓存了旧状态。`am force-stop` + `am start` 即可。

### 6.5 连续失败

**不要无限重试。** 连续 3 次失败就停下来，告诉用户：

> 当前状态可能需要重启设备后重来。要不要现在重启？

重启是最有效的"重置"。`posix_timers_cache` 的 baseline 会随着系统运行时间变化，有时候就是跑不进去。

---

## 7. 用户可能会问的问题

| 问题 | 回答要点 |
|---|---|
| 这是永久的吗 | 不是，重启就失效，这是设计目标 |
| 会不会变砖 | 单次不会（panic 自动恢复）。但**换机型/换系统版本会**，所以有指纹校验 |
| 会丢数据吗 | 正常不会；panic 时未保存的数据会丢 |
| 为什么这么慢 | 内核 slab 竞争，要反复试 |
| 为什么每次都要开 Shizuku | 它是用户态进程，不在 init 服务表里，重启就没了 |
| 能装 Magisk 模块吗 | 不能，这不是 Magisk |
| 关掉以后有残留吗 | 没有，重启即清；但本次开机内内核里有 dangling timer，可能随机 panic |

---

## 8. 收尾

### 如果用户要关掉

```bash
# 方式一：App 里点「关闭 root（重启）」
adb -s $S shell 'input tap <btnDisable 坐标>'

# 方式二：直接重启
adb reboot
```

重启后验证：

```bash
adb wait-for-device && sleep 25
adb -s $S shell 'su -c id 2>&1'   # 应该是 not found
adb -s $S shell 'grep -c kernelsu /proc/modules'   # 0
adb -s $S shell getenforce        # Enforcing
```

### 给用户的总结格式

用表格，不要贴原始日志。例如：

| 项目 | 结果 |
|---|---|
| root | 已开启，`uid=0` / `u:r:ksu:s0` |
| 内核模块 | `kernelsu 233472 1 - Live` |
| 有效范围 | 本次开机内，重启失效 |
| 重启次数 | 1 次（panic，已自动恢复） |

并提醒：

> 用完了点「关闭 root（重启）」—— 顺便清掉内核里那颗延迟炸弹。

---

## 9. 为什么必须用 pty（如果你要自己实现命令行走法）

exploit 拿到 root 后执行的是：

```c
execl("/system/bin/sh", "sh", "-i", NULL);
```

`sh -i` 在没有 tty 时会**立刻退出**（打印 `sh: can't find tty fd`）。退出后你注入的命令会被**外层 uid=2000 的 shell** 接走执行——表现是"命令好像执行了但完全没有 root 效果"。

这个坑我踩了很久。正确做法是用 `assets/ptyrun` 先 `posix_openpt()` 建一个 pty，再在里面跑 exploit：

```bash
cd /data/local/tmp/ksu-root && ./ptyrun ./pk64560 --run
```

用 `adb` 手动验证时对应的是 `adb shell -tt`（两个 t）。

---

## 10. 命令速查

```bash
# 状态一览
adb -s $S shell 'getprop ro.build.fingerprint; getenforce; su -c id 2>&1; grep -c kernelsu /proc/modules'

# Shizuku 启动
BASE=$(adb -s $S shell 'pm path moe.shizuku.privileged.api' | tr -d '\r' | sed 's/^package://; s|/base.apk$||')
adb -s $S shell "setsid $BASE/lib/arm64/libshizuku.so --apk=$BASE/base.apk > /tmp/shiz.log 2>&1 < /dev/null &"

# 跑 exploit（命令行全流程，跳过 App UI）
adb -s $S shell 'sh /data/local/tmp/ksu-root/kp_arm.sh'
printf 'sh /data/local/tmp/ksu-root/ksu_load_setup.sh\nexit\n' \
  | adb -s $S shell -tt 'cd /data/local/tmp/ksu-root && ./pk64560 --run'

# 验证
adb -s $S shell 'su -c id'
adb -s $S shell 'grep -i kernelsu /proc/modules'

# 关闭
adb reboot
```
