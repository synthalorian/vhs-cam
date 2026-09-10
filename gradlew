#!/bin/bash
# Simple gradlew wrapper - downloads gradle if needed

APP_HOME="$(cd "$(dirname "$0")" && pwd)"
GRADLE_VERSION="8.13"
GRADLE_URL="https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip"
GRADLE_DIR="$APP_HOME/.gradle/wrapper"
GRADLE_ZIP="$GRADLE_DIR/gradle-${GRADLE_VERSION}-bin.zip"
GRADLE_HOME="$GRADLE_DIR/gradle-${GRADLE_VERSION}"

# Download gradle if not present
if [ ! -d "$GRADLE_HOME" ]; then
    echo "Downloading Gradle ${GRADLE_VERSION}..."
    mkdir -p "$GRADLE_DIR"
    curl -L "$GRADLE_URL" -o "$GRADLE_ZIP"
    unzip -q "$GRADLE_ZIP" -d "$GRADLE_DIR"
    rm "$GRADLE_ZIP"
fi

# Run gradle
exec "$GRADLE_HOME/bin/gradle" "$@"
