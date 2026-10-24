#!/usr/bin/env bash
# Fetch the jars listed in libs.txt from Maven Central into DEST_DIR (default: <repo>/lib).
# Each jar is verified against its .jar.sha1; files already present with a matching
# SHA-1 are skipped; a mismatch after download is a hard failure.
#
# Usage: tools/macos-arm64/fetch-libs.sh [DEST_DIR]
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
LIBS_FILE="$SCRIPT_DIR/libs.txt"
DEST_DIR="${1:-$REPO_ROOT/lib}"
BASE_URL="https://repo1.maven.org/maven2"

mkdir -p "$DEST_DIR"

sha1_of() { shasum -a 1 "$1" | cut -c1-40; }

fail=0
while IFS= read -r line || [ -n "$line" ]; do
  line="${line%%#*}"
  line="$(echo "$line" | tr -d '[:space:]')"
  [ -z "$line" ] && continue

  IFS=':' read -r group artifact version classifier extra <<<"$line"
  if [ -z "$group" ] || [ -z "$artifact" ] || [ -z "$version" ] || [ -n "${extra:-}" ]; then
    echo "ERROR invalid coordinate: $line" >&2
    fail=1
    continue
  fi

  name="$artifact-$version${classifier:+-$classifier}.jar"
  group_path="$(echo "$group" | tr . /)"
  url="$BASE_URL/$group_path/$artifact/$version/$name"
  dest="$DEST_DIR/$name"

  expected="$(curl -fsSL "$url.sha1" | tr -d '[:space:]' | cut -c1-40)" || expected=""
  if ! [[ "$expected" =~ ^[0-9a-fA-F]{40}$ ]]; then
    echo "ERROR cannot fetch SHA-1 for $name ($url.sha1)" >&2
    fail=1
    continue
  fi
  expected="$(echo "$expected" | tr 'A-F' 'a-f')"

  if [ -f "$dest" ] && [ "$(sha1_of "$dest")" = "$expected" ]; then
    echo "skip  $name"
    continue
  fi

  tmp="$dest.part"
  if ! curl -fsSL -o "$tmp" "$url"; then
    echo "ERROR download failed: $url" >&2
    rm -f "$tmp"
    fail=1
    continue
  fi
  actual="$(sha1_of "$tmp")"
  if [ "$actual" != "$expected" ]; then
    echo "ERROR SHA-1 mismatch for $name: expected $expected, got $actual" >&2
    rm -f "$tmp"
    fail=1
    continue
  fi
  mv "$tmp" "$dest"
  echo "fetch $name"
done <"$LIBS_FILE"

exit "$fail"
