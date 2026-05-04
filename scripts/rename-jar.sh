#!/bin/bash
# BTC-CORE: Post-build JAR rename script
# Renames packages and branding in the compiled paperclip jar
# Usage: bash scripts/rename-jar.sh [input-jar] [output-jar]

set -e

INPUT="${1:-aspaper-server/build/libs/aspaper-paperclip-26.1.2.build.19-alpha.jar}"
OUTPUT="${2:-aspaper-server/build/libs/btccore-paperclip-26.1.2-R0.1-SNAPSHOT.jar}"

WORKDIR="/tmp/btccore-rename-$$"
mkdir -p "$WORKDIR"
cd "$WORKDIR"

echo "=== BTC-CORE: JAR Rename ==="
echo "  Input: $INPUT"
echo "  Output: $OUTPUT"

# Extract
jar xf "$OLDPWD/$INPUT" 2>/dev/null || unzip -q "$OLDPWD/$INPUT"
echo "  [OK] Extracted"

# Rename package paths in class files
# com/infernalsuite/asp -> dev/btc/core
find . -path "*/com/infernalsuite/asp/*" -type f | while read f; do
    new=$(echo "$f" | sed 's|com/infernalsuite/asp|dev/btc/core|g')
    mkdir -p "$(dirname "$new")"
    mv "$f" "$new" 2>/dev/null || true
done
echo "  [OK] Package paths renamed"

# Update strings in class files
find . -name "*.class" -type f | while read f; do
    # AdvancedSlimePaper -> BTC Core (branding)
    # com.infernalsuite.asp -> dev.btc.core (in string constants)
    # Only modify if the class file contains these strings
    if strings "$f" 2>/dev/null | grep -q "AdvancedSlimePaper\|com/infernalsuite/asp"; then
        # Use a temp file for binary-safe replacement
        python3 -c "
import sys
data = open('$f', 'rb').read()
data = data.replace(b'AdvancedSlimePaper', b'BTC Core')
data = data.replace(b'com/infernalsuite/asp', b'dev/btc/core')
data = data.replace(b'com.infernalsuite.asp', b'dev.btc.core')
data = data.replace(b'aspaper-server', b'btccore-server')
data = data.replace(b'aspaper-api', b'btccore-api')
open('$f', 'wb').write(data)
" 2>/dev/null || true
    fi
done
echo "  [OK] Strings replaced in class files"

# Update META-INF
find META-INF -type f -exec sed -i 's/com\.infernalsuite\.asp/dev.btc.core/g; s/AdvancedSlimePaper/BTC Core/g; s/InfernalSuite/BTC Studio/g' {} + 2>/dev/null || true

# Update plugin.yml / paper-plugin.yml
find . -name "*.yml" -o -name "*.yaml" | while read f; do
    sed -i 's/com\.infernalsuite\.asp/dev.btc.core/g; s/AdvancedSlimePaper/BTC Core/g' "$f" 2>/dev/null || true
done

# Repackage
jar cf "$OLDPWD/$OUTPUT" . 2>/dev/null || zip -qr "$OLDPWD/$OUTPUT" .
echo "  [OK] Repackaged: $OUTPUT"

# Cleanup
cd "$OLDPWD"
rm -rf "$WORKDIR"

echo "=== BTC-CORE: Rename complete ==="
ls -lh "$OUTPUT"
