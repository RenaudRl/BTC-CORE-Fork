#!/bin/bash
# BTC-CORE: Publish API to local Maven repo for upload
# Usage: bash repo/publish.sh

set -e
cd "$(dirname "$0")/.."

echo "=== Publishing BTC-CORE API to local Maven ==="
./gradlew :api:publishToMavenLocal --offline

echo ""
echo "=== Files to upload ==="
find ~/.m2/repository/dev/btc/core -name "*.jar" -o -name "*.pom" -o -name "*.module" 2>/dev/null | sort

echo ""
echo "Copy these to: borntocraftstudio.net/public/repo/"
echo "Maintain the same directory structure as ~/.m2/repository/"
