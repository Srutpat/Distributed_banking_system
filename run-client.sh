#!/usr/bin/env bash
# Starts an interactive console client.
# Usage: ./run-client.sh [host] [port]   (defaults: localhost 1099)
set -e
cd "$(dirname "$0")"
if [ ! -d out ]; then
  echo "No build found, building first..."
  ./build.sh
fi
java -cp out client.BankClient "$@"
