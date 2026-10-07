#!/usr/bin/env bash
cd "$(dirname "$0")"

shopt -s nullglob 2>/dev/null || true
for pidfile in .pids/*.pid; do
  [ -f "$pidfile" ] || continue
  pid=$(cat "$pidfile")
  name=$(basename "$pidfile" .pid)
  # First try to kill any child processes (e.g. java JVMs) spawned by mvn
  if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
    # kill children of the mvn process, ignore errors
    pkill -P "$pid" 2>/dev/null || true
    kill "$pid" 2>/dev/null || true
    echo "Stopped $name (PID $pid)"
  else
    echo "PID for $name ($pid) not running; removing pidfile"
  fi
  rm -f "$pidfile"
done

# Cleanup any stray mvn exec:java processes that might have been started
# without pidfiles (safe fallback)
pkill -f "exec:java" 2>/dev/null || true

echo "Done."
