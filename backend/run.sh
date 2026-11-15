#!/bin/sh
# Build and start the Marswalk Planner, then open http://localhost:8080
set -e
cd "$(dirname "$0")/.."
sh backend/build.sh
exec java -cp backend/out marswalk.Main "$@"
