#!/system/bin/sh
# 在 exploit 拿到的 root shell 里执行：加载官方 KernelSU LKM（uapi=6）并确认管理器能识别。
# 由 App 注入，不直接面向用户；工作目录通过脚本自身位置推导，便于整体搬迁。
DIR=$(cd "$(dirname "$0")" 2>/dev/null && pwd)
[ -z "$DIR" ] && DIR=/data/local/tmp/ksu-root

export PATH=$DIR:/sbin:/system/bin:/data/local/tmp
echo "=== KSULOAD_BEGIN ==="
echo "id: $(id 2>&1)"
echo "enforcing: $(getenforce 2>&1)"
echo "--- late-load (official ko embedded in ksud) ---"
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
ls -l /system/bin/su 2>&1
echo "=== KSULOAD_END ==="
sleep 2
