#!/usr/bin/env bash
# ba-analysis 数据分析服务 启动/停止/状态 脚本 (Ubuntu, java -jar)
# 用法: ./start.sh [start|stop|restart|status|log|cert|setcap]
# 职责: 只读MySQL运行分析器并提供Web接口 (HTTPS, 端口443)。不访问币安, 无需出网。
#
# HTTPS: application.yml 已开 SSL, 读 ./keystore.p12 (自签名证书, 不入库)。
#   - 首次部署先生成证书:   ./start.sh cert
#   - 443 是特权端口, 非 root 需先授权: ./start.sh setcap   (或用 sudo ./start.sh)
#   - 敏感配置 (DB_PASSWORD / SSL_KEYSTORE_PASSWORD) 放本地 .env (不入库), 启动时自动加载。
set -euo pipefail

JAR='ba-analysis-1.0.0.jar'   # mvn package -DskipTests 后在 target/ 下
LOG='analysis.log'
KEYSTORE='keystore.p12'
KEY_ALIAS='ba-analysis'
JAVA_OPTS='-Djava.net.preferIPv4Stack=true'

cd "$(dirname "$0")"

# 加载本地 .env (不入库), 提供 DB_PASSWORD / SSL_KEYSTORE_PASSWORD 等敏感信息
if [ -f .env ]; then set -a; . ./.env; set +a; fi

pid_of() { pgrep -f "java .*${JAR}" || true; }

# 生成自签名 PKCS12 证书 (SAN 含公网IP + localhost, 有效期10年)。已存在则提示覆盖。
gen_cert() {
  local pass="${SSL_KEYSTORE_PASSWORD:-changeit}"
  if [ -f "$KEYSTORE" ]; then
    read -r -p "$KEYSTORE 已存在, 覆盖重新生成? [y/N] " ans
    [ "${ans:-N}" = "y" ] || { echo "已取消。"; return; }
  fi
  keytool -genkeypair -alias "$KEY_ALIAS" -keyalg RSA -keysize 2048 -validity 3650 \
    -storetype PKCS12 -keystore "$KEYSTORE" -storepass "$pass" \
    -dname "CN=ba-analysis, OU=dev, O=ba, L=NA, ST=NA, C=CN" \
    -ext "SAN=ip:43.155.216.33,dns:localhost,ip:127.0.0.1"
  echo "已生成 $KEYSTORE (alias=$KEY_ALIAS, storepass=$pass)。"
}

# 授权 java 绑定 443 特权端口 (非 root 部署用; 需 sudo, 一次性)。
authorize_port() {
  local javabin; javabin="$(readlink -f "$(command -v java)")"
  echo "授权 java 绑定特权端口: $javabin"
  sudo setcap 'cap_net_bind_service=+ep' "$javabin"
  echo "完成。现在非 root 也能监听 443。"
}

start() {
  local pid; pid=$(pid_of)
  if [ -n "$pid" ]; then echo "已在运行 (PID $pid), 不重复启动。"; exit 0; fi
  local jarpath="$JAR"
  [ -f "$jarpath" ] || jarpath="target/$JAR"
  if [ ! -f "$jarpath" ]; then
    echo "找不到 $JAR — 先执行 mvn package -DskipTests。"; exit 1
  fi
  if [ ! -f "$KEYSTORE" ]; then
    echo "找不到 $KEYSTORE — 先执行 ./start.sh cert 生成自签名证书。"; exit 1
  fi
  nohup java $JAVA_OPTS -jar "$jarpath" > "$LOG" 2>&1 &
  sleep 3
  pid=$(pid_of)
  if [ -z "$pid" ]; then
    echo "启动失败, 进程未存活。最后几行日志:"; tail -n 8 "$LOG"
    if grep -qiE "Permission denied|cannot bind|Address already in use|EACCES" "$LOG"; then
      echo "—— 443 绑定失败: 用 sudo ./start.sh, 或先 ./start.sh setcap 授权 java。"
    fi
    exit 1
  fi
  echo "ba-analysis 已启动 (PID $pid), HTTPS https://<host>/ (443)。日志: $LOG (用 ./start.sh log 跟踪)"
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
  cert)    gen_cert ;;
  setcap)  authorize_port ;;
  *)       echo "用法: $0 [start|stop|restart|status|log|cert|setcap]"; exit 1 ;;
esac
