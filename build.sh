#!/usr/bin/env bash
# Compiles every source file into ./out
set -e
cd "$(dirname "$0")"
rm -rf out
mkdir out
javac -d out $(find src -name "*.java")
echo "Build OK -> ./out"
