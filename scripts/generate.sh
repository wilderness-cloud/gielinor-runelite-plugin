#!/usr/bin/env sh
# Regenerates the wire contract's Java from buf.gen.yaml (buf 1.73.0).
set -eu
cd "$(dirname "$0")/.."
rm -rf src/main/java/cloud/tobbbles/gielinor/runelite/v1
buf generate
