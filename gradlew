#!/usr/bin/env sh
# MIRA Gradle bootstrap launcher (Gradle 8.9)
set -eu
GRADLE_VERSION="8.9"
GRADLE_HOME_BASE="${GRADLE_USER_HOME:-$HOME/.gradle}/mira-bootstrap"
GRADLE_HOME="$GRADLE_HOME_BASE/gradle-$GRADLE_VERSION"
DIST_ZIP="$GRADLE_HOME_BASE/gradle-$GRADLE_VERSION-bin.zip"

if [ ! -x "$GRADLE_HOME/bin/gradle" ]; then
  mkdir -p "$GRADLE_HOME_BASE"
  if [ ! -f "$DIST_ZIP" ]; then
    if command -v curl >/dev/null 2>&1; then
      curl -fL "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip" -o "$DIST_ZIP"
    elif command -v wget >/dev/null 2>&1; then
      wget "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip" -O "$DIST_ZIP"
    else
      echo "ERROR: curl or wget is required to download Gradle." >&2
      exit 1
    fi
  fi
  if ! command -v unzip >/dev/null 2>&1; then
    echo "ERROR: unzip is required to extract Gradle." >&2
    exit 1
  fi
  unzip -q -o "$DIST_ZIP" -d "$GRADLE_HOME_BASE"
fi
exec "$GRADLE_HOME/bin/gradle" "$@"
