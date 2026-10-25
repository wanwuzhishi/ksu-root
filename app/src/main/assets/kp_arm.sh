#!/system/bin/sh
T=/sys/kernel/tracing
echo "ARM_BEGIN"
if grep -q window0 $T/kprobe_events 2>/dev/null; then
  echo "PROBE_ALREADY_PRESENT"
else
  i=0
  while [ $i -lt 300 ]; do
    if echo "p:irq/window0 posix_cpu_timer_del+72" >> $T/kprobe_events 2>/dev/null; then
      echo "PROBE_OK attempt=$i"
      break
    fi
    i=$((i+1))
    sleep 1
  done
fi
echo "=== kprobe_events ==="
cat $T/kprobe_events
echo 16384 > $T/buffer_size_kb 2>/dev/null && echo "BUF_OK"
echo 1 > $T/events/irq/enable 2>/dev/null && echo "IRQ_ENABLE_OK"
echo 1 > $T/tracing_on 2>/dev/null && echo "TRACING_ON_OK"
echo "tracing_on=$(cat $T/tracing_on)"
echo "ARM_DONE"
