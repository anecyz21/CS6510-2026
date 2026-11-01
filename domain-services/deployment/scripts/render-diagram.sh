#!/usr/bin/env bash
# Renders the first Mermaid block of ARCHITECTURE.md to architecture-mermaid.svg (005 research R12).
#
# architecture.svg, the image the README and ARCHITECTURE.md embed, is drawn by hand from the same
# topology so it needs no toolchain. This script produces a generated copy of the Mermaid source for
# comparison, using a pinned mermaid-cli through npx (Node 18+) or, failing that, Docker.
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
version="11.4.0"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
awk '/^```mermaid$/{inside=1; next} /^```$/{if (inside) exit} inside' "$here/ARCHITECTURE.md" > "$work/diagram.mmd"
[ -s "$work/diagram.mmd" ] || { echo "no mermaid block in ARCHITECTURE.md" >&2; exit 1; }
if command -v npx >/dev/null 2>&1; then
  npx --yes "@mermaid-js/mermaid-cli@$version" -i "$work/diagram.mmd" -o "$here/architecture-mermaid.svg" -b white
else
  docker run --rm -v "$work:/data" "minlag/mermaid-cli:$version" -i /data/diagram.mmd -o /data/out.svg -b white
  cp "$work/out.svg" "$here/architecture-mermaid.svg"
fi
echo "wrote $here/architecture-mermaid.svg"
