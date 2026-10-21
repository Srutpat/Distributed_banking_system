#!/usr/bin/env bash
# Starts the central bank RMI server in the foreground.
# Data persists to ./data/ across restarts. Ctrl+C to stop.
set -e
cd "$(dirname "$0")"
if [ ! -d out ]; then
  echo "No build found, building first..."
  ./build.sh
fi
java -cp out server.BankServer
