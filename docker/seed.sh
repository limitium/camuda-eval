#!/bin/sh
# The repository every instance meets in, made once: a bare repository whose first commit holds the
# demo's rules (docker/seed: dmn/*.dmn and yaml/*.yaml). Left alone if it already exists.
set -e
REPO=/git/rules.git
if [ -d "$REPO" ]; then
  echo "seed: $REPO exists, left as it is"
  exit 0
fi
git init -q --bare --initial-branch=main "$REPO"
WORK=$(mktemp -d)
git -C "$WORK" init -q --initial-branch=main
mkdir -p "$WORK/dmn" "$WORK/yaml"
: > "$WORK/dmn/.gitkeep"
: > "$WORK/yaml/.gitkeep"
cp /seed/dmn/*.dmn "$WORK/dmn/" 2>/dev/null || true
cp /seed/yaml/*.yaml "$WORK/yaml/" 2>/dev/null || true
git -C "$WORK" add -A
git -C "$WORK" -c user.name=camunda-eval -c user.email=camunda-eval@localhost commit -q -m "initialise rule repository"
git -C "$WORK" push -q "$REPO" main
echo "seed: $REPO created with $(ls /seed/dmn 2>/dev/null | tr '\n' ' ')"
