---
name: Bug report
about: 报告一个问题（请先读 AI-GUIDE.md 第 6 节「故障处理」）
title: '[BUG] '
labels: bug
assignees: ''
---

## 环境

<!-- 下面这几条能直接排除掉 80% 的"看起来是 bug 其实是版本不对" -->

```
设备型号：
系统版本（设置 → 我的设备 → 系统版本）：
内核版本（adb shell uname -r）：
Shizuku 版本：
KernelSU 管理器版本：
App 版本：
```

指纹（**必须**，`adb shell getprop ro.build.fingerprint`）：

```
```

## 现象

<!-- 卡在哪一步？日志最后几行是什么？ -->

## 日志

<!-- 优先用 App 的「复制日志」按钮粘过来，或「导出日志」后贴文件 -->

```
```

## 关键状态

```bash
adb shell getenforce                              # Enforcing / Permissive
adb shell 'grep -c kernelsu /proc/modules'        # 0 / 1
adb shell 'su -c id 2>&1'                         # uid=0 / inaccessible
adb shell 'cut -d. -f1 /proc/uptime'              # 秒
adb shell 'ps -A | grep shizuku_server'           # 有没有
```

## 是否 panic 重启过

<!-- 如果设备突然黑屏重启过，说明触发了利用链的固有概率，不是 bug -->

- [ ] 没有重启
- [ ] 重启过 1 次
- [ ] 重启过多次
