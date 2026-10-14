#!/usr/bin/env bash
# Builds src/main/tailwind/app.css with the Tailwind CSS standalone CLI (no Node.js).
# Usage:
#   ./scripts/tailwind.sh                          # one-off minified build into target/classes
#   ./scripts/tailwind.sh --watch                  # rebuild on template changes (run next to spring-boot:run)
#   ./scripts/tailwind.sh --output <file> [...]    # used by the Maven build
set -euo pipefail

TAILWIND_VERSION="4.3.3"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOOLS_DIR="$ROOT/.tools"

case "$(uname -s)-$(uname -m)" in
  Linux-x86_64)               ASSET="tailwindcss-linux-x64";   SHA256="dc61b3ac6b8c9ca874c0cc4c57b2409791a64c5540404ca5f5367360babc313a" ;;
  Linux-aarch64|Linux-arm64)  ASSET="tailwindcss-linux-arm64"; SHA256="55fd0b241214eff3de1e8ee4f22796662f2d2e7a49bcfca7477cfd0bac398195" ;;
  Darwin-arm64)               ASSET="tailwindcss-macos-arm64"; SHA256="cdf646702987a743464dff4d9c60fd4480d1c1e73dd819a9a67f1078815dce9d" ;;
  Darwin-x86_64)              ASSET="tailwindcss-macos-x64";   SHA256="7922e0953f2110c05976e3bf58f14e643d90427575e766b7d433f5f80cbee7e1" ;;
  *) echo "Unsupported platform: $(uname -s)-$(uname -m). On Windows use scripts\\tailwind.cmd." >&2; exit 1 ;;
esac

BIN="$TOOLS_DIR/$ASSET-$TAILWIND_VERSION"

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

if [[ ! -x "$BIN" ]]; then
  mkdir -p "$TOOLS_DIR"
  echo "Downloading Tailwind CSS v$TAILWIND_VERSION ($ASSET)..."
  curl -fsSL -o "$BIN.tmp" "https://github.com/tailwindlabs/tailwindcss/releases/download/v$TAILWIND_VERSION/$ASSET"
  actual="$(sha256_of "$BIN.tmp")"
  if [[ "$actual" != "$SHA256" ]]; then
    rm -f "$BIN.tmp"
    echo "Checksum mismatch for $ASSET: expected $SHA256, got $actual" >&2
    exit 1
  fi
  chmod +x "$BIN.tmp"
  mv "$BIN.tmp" "$BIN"
fi

ARGS=(--input "$ROOT/src/main/tailwind/app.css" --cwd "$ROOT")
if [[ " $* " != *" --output "* ]]; then
  ARGS+=(--output "$ROOT/target/classes/static/css/app.css")
fi
if [[ " $* " != *" --watch "* ]]; then
  ARGS+=(--minify)
fi

exec "$BIN" "${ARGS[@]}" "$@"
