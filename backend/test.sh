#!/bin/sh
# Build and run the backend test suite (no extra libraries needed).
set -e
cd "$(dirname "$0")/.."
sh backend/build.sh
java -Xmx1500m -cp backend/out marswalk.TestMain
