#!/bin/sh
# Builds every loader / Minecraft version of this branch and copies the jars to dist/.
set -e
cd "$(dirname "$0")"
mkdir -p dist
for d in fabric-* forge-*; do
  [ -f "$d/gradlew" ] || continue
  echo "== $d"
  (cd "$d" && sh ./gradlew build --no-daemon -q)
  for j in "$d"/build/libs/*.jar; do
    case "$j" in *-sources.jar) ;; *) cp "$j" dist/ ;; esac
  done
done
echo "Jars are in dist/"
