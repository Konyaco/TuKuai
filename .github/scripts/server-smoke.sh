#!/usr/bin/env bash
# Starts a dedicated server for one loader on one Minecraft version and passes once the
# world has loaded with TuKuai initialized. Usage: server-smoke.sh fabric|neoforge <minecraft>
set -euo pipefail

loader="$1"
minecraft="$2"
project="${loader}-${minecraft}"
run_dir="versions/${minecraft}/${loader}/runs/server"
log="${run_dir}/logs/latest.log"
mkdir -p "${run_dir}"
echo "eula=true" > "${run_dir}/eula.txt"
rm -f "${log}"

setsid ./gradlew ":${project}:runServer" --no-daemon > "${RUNNER_TEMP:-/tmp}/${project}-server.out" 2>&1 &
pid=$!
trap 'kill -- -"${pid}" 2>/dev/null || true' EXIT

for _ in $(seq 1 120); do
    if ! kill -0 "${pid}" 2>/dev/null; then
        echo "Server for ${project} exited before it finished loading" >&2
        tail -n 80 "${RUNNER_TEMP:-/tmp}/${project}-server.out" >&2
        exit 1
    fi
    if [[ -f "${log}" ]] && grep -q 'Done (' "${log}"; then
        if grep -q "TuKuai loaded on" "${log}"; then
            echo "Server for ${project} loaded with TuKuai"
            exit 0
        fi
        echo "Server for ${project} started without TuKuai" >&2
        exit 1
    fi
    sleep 5
done

echo "Server for ${project} did not finish loading in 10 minutes" >&2
tail -n 80 "${RUNNER_TEMP:-/tmp}/${project}-server.out" >&2
exit 1
