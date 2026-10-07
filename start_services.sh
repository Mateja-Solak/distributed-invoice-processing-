#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"

mvn -q -DskipTests install

mkdir -p logs .pids
rm -f .pids/*.pid

start() {
  local module="$1"
  local mainClass="$2"
  echo "Starting $module ($mainClass) ..."
  nohup mvn -q -pl "$module" -Dexec.mainClass="$mainClass" -Dexec.classpathScope=compile exec:java \
    > "logs/$module.log" 2>&1 &
  echo $! > ".pids/$module.pid"
}

# Redoslijed: prvo servisi koji samo slusaju (RMI server, Redis/MQ consumeri, REST),
# na kraju Watcher (producer) - da nijedna poruka poslata dok se sistem podize ne bude
# izgubljena jer jos niko ne slusa.
start validator ValidatorServer
sleep 2
start aggregator AggregatorService
start restapi RestApiApp
start enrichment EnrichmentService
start approval ApprovalService
sleep 1
start approval-gui ApprovalGuiClient
start monitor-gui MonitorClient
sleep 1
start parser ParserService
sleep 2
start watcher WatcherService
