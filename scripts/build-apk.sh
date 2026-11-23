#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -z "${JAVA_HOME:-}" && -x /usr/libexec/java_home ]]; then
  export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
fi
if [[ -z "${JAVA_HOME:-}" ]]; then
  echo 'Set JAVA_HOME to your JDK 17 installation.' >&2
  exit 1
fi
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
build_tools="$sdk/build-tools/35.0.1"
if [[ ! -x "$build_tools/apksigner" ]]; then
  echo 'Install Android SDK Build-Tools 35.0.1 and set ANDROID_HOME.' >&2
  exit 1
fi
# Keep this directory backed up: future APK updates must use the same key.
signing_dir="${NOTION_SIGNING_DIR:-$HOME/.android/notion-todo-widget-signing}"
mkdir -p "$signing_dir"
chmod 700 "$signing_dir"
umask 077
if [[ ! -f "$signing_dir/release.p12" ]]; then
  if [[ ! -f "$signing_dir/password" ]]; then
    openssl rand -hex 32 > "$signing_dir/password"
  fi
  "$JAVA_HOME/bin/keytool" -genkeypair -keystore "$signing_dir/release.p12" \
    -storetype PKCS12 -alias notion-tasks -keyalg RSA -keysize 3072 -validity 10000 \
    -storepass:file "$signing_dir/password" -keypass:file "$signing_dir/password" \
    -dname 'CN=Notion Tasks Personal Install' >/dev/null 2>&1
fi
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleRelease --console=plain
mkdir -p artifacts
"$build_tools/zipalign" -f -p 4 app/build/outputs/apk/release/app-release-unsigned.apk artifacts/notion-tasks-aligned.apk
"$build_tools/apksigner" sign --ks "$signing_dir/release.p12" --ks-key-alias notion-tasks \
  --ks-pass "file:$signing_dir/password" --out artifacts/notion-tasks.apk artifacts/notion-tasks-aligned.apk
rm artifacts/notion-tasks-aligned.apk
"$build_tools/apksigner" verify --verbose artifacts/notion-tasks.apk
shasum -a 256 artifacts/notion-tasks.apk > artifacts/notion-tasks.apk.sha256
printf '\nInstallable APK: %s/artifacts/notion-tasks.apk\n' "$PWD"
