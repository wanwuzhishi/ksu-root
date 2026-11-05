#!/system/bin/sh
# 在 exploit 拿到的 root shell 里执行：加载管理器自带的 LKM（uapi=6）并确认能被识别。
# 由 App 注入，不直接面向用户；工作目录通过脚本自身位置推导，便于整体搬迁。
#
# 兼容 KernelSU 与 SukiSU Ultra：两者是同源分支，late-load / feature 的参数名与
# 语义一致，所以这套脚本两边通用。换分支只需要换 $DIR/ksud，脚本本身不用动。
DIR=$(cd "$(dirname "$0")" 2>/dev/null && pwd)
[ -z "$DIR" ] && DIR=/data/local/tmp/ksu-root

export PATH=$DIR:/sbin:/system/bin:/data/local/tmp
echo "=== KSULOAD_BEGIN ==="
echo "id: $(id 2>&1)"
echo "enforcing: $(getenforce 2>&1)"
echo "ksud: $("$DIR/ksud" -V 2>&1)"
echo "--- late-load (ko embedded in ksud) ---"
"$DIR/ksud" late-load --allow-shell > /data/local/tmp/late5.log 2>&1
echo "late_rc=$?"
sleep 4
grep -i kernelsu /proc/modules 2>&1
echo "enforcing_after: $(getenforce 2>&1)"
echo "--- debug info ---"
"$DIR/ksud" debug info 2>&1
echo "--- feature list ---"
"$DIR/ksud" feature list 2>&1 | head -30
echo "--- su path check ---"
# su 是 LKM 起来之后 install 到 /system/bin/su 的，会晚几秒到几分钟出现。
# 这里等到出现为止，最多 120 秒，每 3 秒探一次。
i=0
while [ $i -lt 40 ]; do
  if [ -x /system/bin/su ]; then
    echo "su_ready_after=$((i * 3))s"
    break
  fi
  i=$((i + 1))
  sleep 3
done
ls -l /system/bin/su 2>&1

# shell_su_adb_only（feature ID=6）默认开启，它只放行由 adbd 或 ksu 域派生的
# 进程使用 su。Shizuku 的 shizuku_server 是 setsid 起的、父进程被 init 接管（ppid=1），
# 不在放行范围内，于是 App→Shizuku→shell 这条链上 `su` 表现为「inaccessible or not found」
# （连 /system/bin/su 的 exec 都被拦掉，不是权限不足而是找不到）。adb shell 直连因为父进程
# 就是 adbd，反而一直正常，很容易把问题误判成「su 还没生成」。
# 关掉它：本工具的调用链天然不经过 adbd，留着必然失败。
echo "--- disable shell_su_adb_only ---"
"$DIR/ksud" feature set shell_su_adb_only 0 2>&1
echo "feature_rc=$?"
"$DIR/ksud" feature list 2>&1 | grep -A1 shell_su_adb_only
echo "=== KSULOAD_END ==="
sleep 2
