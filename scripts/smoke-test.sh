#!/usr/bin/env bash
# Smoke test for the container image: start it, talk to it over TCP, stop it with SIGTERM,
# start a new container on the same volume, and check the data survived.
# Usage: scripts/smoke-test.sh [image]      (default image: redis-lite:ci)
set -euo pipefail

IMAGE="${1:-redis-lite:ci}"
PORT=6380
NAME=kv-smoke
VOLUME="kv-smoke-data-$$"

cleanup() {
  docker rm -f "$NAME" >/dev/null 2>&1 || true
  docker volume rm "$VOLUME" >/dev/null 2>&1 || true
}
trap cleanup EXIT

wait_ready() {
  # A published Docker port can accept connections (Docker's proxy listens on it) before the
  # JVM inside the container is listening, so "I can connect" does not mean "the server is up".
  # Wait until the server really answers a command.
  for _ in $(seq 1 40); do
    if [ "$(docker inspect -f '{{.State.Running}}' "$NAME" 2>/dev/null)" != "true" ]; then
      echo "container is not running" >&2
      docker logs "$NAME" >&2 || true
      return 1
    fi
    if [ "$(send 'GET ready-check' 2>/dev/null || true)" = '$-1' ]; then
      return 0
    fi
    sleep 1
  done
  echo "server did not become ready" >&2
  docker logs "$NAME" >&2 || true
  return 1
}

# send "COMMAND": sends one command followed by QUIT and prints the first reply line.
send() {
  printf '%s\r\nQUIT\r\n' "$1" \
    | timeout 10 bash -c "exec 3<>/dev/tcp/127.0.0.1/$PORT; cat >&3; cat <&3" \
    | sed -n '1p' \
    | tr -d '\r'
}

expect() {   # expect ACTUAL EXPECTED
  if [ "$1" != "$2" ]; then
    echo "FAIL: expected '$2' but got '$1'" >&2
    docker logs "$NAME" >&2 || true
    exit 1
  fi
  echo "ok: $2"
}

start_container() {
  docker run -d --name "$NAME" -p "$PORT:6380" -v "$VOLUME":/data "$IMAGE" >/dev/null
  wait_ready
}

docker volume create "$VOLUME" >/dev/null

echo "== first run =="
start_container
expect "$(send 'SET persisted yes')" '+OK'
expect "$(send 'GET persisted')" 'yes'
expect "$(send 'INCR visits')" ':1'

echo "== docker stop (SIGTERM must trigger the final snapshot) =="
docker stop "$NAME" >/dev/null
docker rm "$NAME" >/dev/null

echo "== second run on the same volume =="
start_container
expect "$(send 'GET persisted')" 'yes'
expect "$(send 'INCR visits')" ':2'

echo "smoke test passed"