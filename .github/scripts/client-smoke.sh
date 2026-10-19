#!/usr/bin/env bash
# Starts the NeoForge client under a virtual display and passes once it has loaded past
# the loading screen with TuKuai in it and nothing from TuKuai went wrong. The Fabric
# client is covered more fully by its game test. Run it inside xvfb-run.
set -euo pipefail

run_dir="neoforge/runs/client"
log="${run_dir}/logs/latest.log"
out="${RUNNER_TEMP:-/tmp}/neoforge-client.out"
mkdir -p "${run_dir}"
rm -f "${log}"

setsid ./gradlew ":neoforge:runClient" --no-daemon > "${out}" 2>&1 &
pid=$!
trap 'kill -- -"${pid}" 2>/dev/null || true' EXIT

fail() {
    echo "$1" >&2
    [[ -f "${log}" ]] && grep -iE "error|exception|crash" "${log}" | tail -n 40 >&2
    tail -n 80 "${out}" >&2
    exit 1
}

for _ in $(seq 1 120); do
    kill -0 "${pid}" 2>/dev/null || fail "The NeoForge client exited before it finished loading"
    if [[ -f "${log}" ]] && grep -q "TuKuai client ready" "${log}"; then
        # Give anything that runs after the first screen a moment to complain.
        sleep 10
        if grep -E "/(ERROR|FATAL)\]" "${log}" | grep -qi "tukuai"; then
            fail "The NeoForge client logged an error from TuKuai"
        fi
        if grep -q "Crash report\|---- Minecraft Crash Report" "${log}" "${out}"; then
            fail "The NeoForge client crashed"
        fi
        echo "NeoForge client loaded with TuKuai"
        exit 0
    fi
    sleep 5
done

fail "The NeoForge client did not finish loading in 10 minutes"
