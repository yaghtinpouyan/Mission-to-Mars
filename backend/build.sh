#!/bin/sh
# Compile the backend (needs a JDK 17+, e.g. `java -version` and `javac -version` both work).
set -e
cd "$(dirname "$0")/.."
mkdir -p backend/out
javac -Xlint:all -d backend/out backend/src/main/java/marswalk/*.java backend/src/test/java/marswalk/*.java
echo "Built into backend/out"
