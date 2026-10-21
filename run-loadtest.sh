#!/usr/bin/env bash
# Runs the concurrency stress test against a running server.
# Usage: ./run-loadtest.sh [host] [port] [threads] [opsPerThread]
#   defaults: localhost 1099 50 40  (= 2000 concurrent operations)
set -e
cd "$(dirname "$0")"
if [ ! -d out ]; then
  echo "No build found, building first..."
  ./build.sh
fi
java -cp out client.LoadTestClient "$@"
