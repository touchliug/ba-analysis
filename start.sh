#!/usr/bin/env bash
# ba-analysis 数据分析服务 启动/停止/状态 脚本 (Ubuntu, java -jar)
# 用法: ./start.sh [start|stop|restart|status|log]
# 职责: 只读MySQL运行分析器并提供Web接口 (端口8080)。不访问币安, 无需出网。
set -euo pipefail

JAR='ba-analysis-1.0.0.jar'   # mvn package -DskipTests 后在 target/ 下
LOG='analysis.log'
JAVA_OPTS='-Djava.net.preferIPv4Stack=true'

cd "$(dirname "$0")"

pid_of() { pgrep -f "java .*${JAR}" || true; }

start() {
  local pid; pid=$(pid_of)
  if [ -n "$pid" ]; then echo "已在运行 (PID $pid), 不重复启动。"; exit 0; fi
  local jarpath="$JAR"
  [ -f "$jarpath" ] || jarpath="target/$JAR"
  if [ ! -f "$jarpath" ]; then
    echo "找不到 $JAR — 先执行 mvn package -DskipTests。"; exit 1
  fi
  nohup java $JAVA_OPTS -jar "$jarpath" > "$LOG" 2>&1 &
  sleep 1
  echo "ba-analysis 已启动 (PID $(pid_of)). 日志: $LOG  (用 ./start.sh log 跟踪)"
}

stop() {
  local pid; pid=$(pid_of)
  if [ -z "$pid" ]; then echo "未在运行。"; return; fi
  kill "$pid"; echo "已发送停止信号 (PID $pid)。"
}

case "${1:-start}" in
  start)   start ;;
  stop)    stop ;;
  restart) stop; sleep 2; start ;;
  status)  pid=$(pid_of); [ -n "$pid" ] && echo "运行中 (PID $pid)" || echo "未运行" ;;
  log)     tail -f "$LOG" ;;
  *)       echo "用法: $0 [start|stop|restart|status|log]"; exit 1 ;;
esac
