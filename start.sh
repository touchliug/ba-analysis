#!/usr/bin/env bash
# ba-analysis 数据分析服务 启动/停止/状态 脚本 (Ubuntu, java -jar)
# 用法: ./start.sh [start|stop|restart|status|log|config|cert|setcap]
# 职责: 只读MySQL运行分析器并提供Web接口 (HTTPS, 端口443)。不访问币安, 无需出网。
#
# 首次部署:
#   1. ./start.sh config   从 config/application.yml.example 生成 config/application.yml (全量配置, 不入库)
#   2. cp .env.example .env 并填入 DB_PASSWORD (配置里 ${DB_PASSWORD} 由它提供)
#   3. ./start.sh cert     生成自签名证书 ./keystore.p12 (不入库)
#   4. ./start.sh setcap   443 是特权端口, 非 root 需授权一次 (或用 sudo ./start.sh)
#   5. ./start.sh
#
# 配置: config/application.yml 逐项覆盖 jar 内打包的 application.yml。该文件是部署时的配置来源,
#       缺失则拒绝启动 (避免静默沿用 jar 内默认值)。.env 启动时自动加载, 为配置里的 ${...} 提供值。
set -euo pipefail

JAR='ba-analysis-1.0.0.jar'   # mvn package -DskipTests 后在 target/ 下
LOG='analysis.log'
CONFIG='config/application.yml'
CONFIG_EXAMPLE='config/application.yml.example'
KEYSTORE='keystore.p12'       # 须与 config/application.yml 的 server.ssl.key-store 一致
KEY_ALIAS='ba-analysis'
JAVA_OPTS='-Djava.net.preferIPv4Stack=true'

cd "$(dirname "$0")"

# 加载本地 .env (不入库), 提供 DB_PASSWORD / SSL_KEYSTORE_PASSWORD 等敏感信息
if [ -f .env ]; then set -a; . ./.env; set +a; fi

pid_of() { pgrep -f "java .*${JAR}" || true; }

# 从模板生成部署配置 (已存在则不覆盖, 防止误抹掉部署机上的修改)。
init_config() {
  if [ -f "$CONFIG" ]; then
    echo "$CONFIG 已存在, 不覆盖。需要重置请先手动删除或备份它。"; return
  fi
  if [ ! -f "$CONFIG_EXAMPLE" ]; then
    echo "找不到模板 $CONFIG_EXAMPLE。"; exit 1
  fi
  cp "$CONFIG_EXAMPLE" "$CONFIG"
  chmod 600 "$CONFIG"
  echo "已生成 $CONFIG (权限 600)。按需修改后再启动; 密码默认取自 .env 的 DB_PASSWORD。"
}

# 启动前检查配置: 文件必须存在; 配置引用了 ${DB_PASSWORD} 时环境里必须有值。
check_config() {
  if [ ! -f "$CONFIG" ]; then
    echo "找不到 $CONFIG — 先执行 ./start.sh config 生成全量配置。"; exit 1
  fi
  if grep -qE '^[^#]*\$\{DB_PASSWORD\}' "$CONFIG" && [ -z "${DB_PASSWORD:-}" ]; then
    echo "$CONFIG 引用了 \${DB_PASSWORD}, 但未设置 — 在 .env 中填写 DB_PASSWORD, 或在配置里直接写明文。"; exit 1
  fi
}

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
  check_config
  if [ ! -f "$KEYSTORE" ]; then
    echo "找不到 $KEYSTORE — 先执行 ./start.sh cert 生成自签名证书。"; exit 1
  fi
  nohup java $JAVA_OPTS -jar "$jarpath" \
    --spring.config.additional-location=file:./config/ > "$LOG" 2>&1 &
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
  config)  init_config ;;
  cert)    gen_cert ;;
  setcap)  authorize_port ;;
  *)       echo "用法: $0 [start|stop|restart|status|log|config|cert|setcap]"; exit 1 ;;
esac
