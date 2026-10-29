#!/bin/sh
# Select an installed Java 21 runtime, or bootstrap a verified Eclipse Temurin JDK 21.
# The script prints only JAVA_HOME to stdout; diagnostics are written to stderr.
set -eu

is_java21_home() {
    candidate=${1:-}
    [ -n "$candidate" ] || return 1
    [ -x "$candidate/bin/java" ] || return 1

    version_output=$("$candidate/bin/java" -version 2>&1 || true)
    printf '%s\n' "$version_output" | grep -Eq 'version "21([.]|")|openjdk 21([.]|[[:space:]])'
}

for candidate in "${TABBY_JAVA_HOME:-}" "${JAVA_HOME:-}" "${JDK_HOME:-}"; do
    if is_java21_home "$candidate"; then
        printf '%s\n' "${candidate%/}"
        exit 0
    fi
done

# macOS provides a reliable system registry for installed JDKs.
if [ -x /usr/libexec/java_home ]; then
    candidate=$(/usr/libexec/java_home -v 21 2>/dev/null || true)
    if is_java21_home "$candidate"; then
        printf '%s\n' "${candidate%/}"
        exit 0
    fi
fi

# Resolve the active PATH entry, then check conventional JDK installation roots.
java_command=$(command -v java 2>/dev/null || true)
if [ -n "$java_command" ]; then
    resolved_command=""
    if command -v readlink >/dev/null 2>&1; then
        resolved_command=$(readlink -f "$java_command" 2>/dev/null || true)
    fi
    if [ -n "$resolved_command" ]; then
        candidate=$(dirname "$(dirname "$resolved_command")")
    else
        candidate=$(dirname "$(dirname "$java_command")")
    fi
    if is_java21_home "$candidate"; then
        printf '%s\n' "${candidate%/}"
        exit 0
    fi
fi

gradle_home=${GRADLE_USER_HOME:-"$HOME/.gradle"}
cached_jdk="$gradle_home/jdks/tabby-temurin-21"
if is_java21_home "$cached_jdk"; then
    printf '%s\n' "$cached_jdk"
    exit 0
fi

for candidate in \
    "${HOME:-/nonexistent}"/.jdks/* \
    "${HOME:-/nonexistent}"/.sdkman/candidates/java/* \
    "${HOME:-/nonexistent}"/.asdf/installs/java/* \
    "${HOME:-/nonexistent}"/.local/share/mise/installs/java/* \
    /usr/lib/jvm/* \
    /usr/java/* \
    /opt/jdk* \
    /opt/java/* \
    /usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
    /Library/Java/JavaVirtualMachines/*/Contents/Home; do
    if is_java21_home "$candidate"; then
        printf '%s\n' "${candidate%/}"
        exit 0
    fi
done

case "$(uname -s)" in
    Linux) os=linux ;;
    Darwin) os=mac ;;
    *)
        printf '%s\n' "Tabby: automatic Java 21 setup is supported on Linux and macOS shells." >&2
        exit 1
        ;;
esac

case "$(uname -m)" in
    x86_64|amd64) architecture=x64 ;;
    aarch64|arm64) architecture=aarch64 ;;
    *)
        printf '%s\n' "Tabby: no automatic Temurin 21 bootstrap mapping for architecture $(uname -m)." >&2
        exit 1
        ;;
esac

if ! command -v curl >/dev/null 2>&1 || ! command -v tar >/dev/null 2>&1 || ! command -v awk >/dev/null 2>&1; then
    printf '%s\n' "Tabby: curl, tar, and awk are required to bootstrap Java 21." >&2
    exit 1
fi

jdk_parent="$gradle_home/jdks"
mkdir -p "$jdk_parent"
tmp_dir=$(mktemp -d "$jdk_parent/.tabby-temurin-21.XXXXXX")
trap 'rm -rf "$tmp_dir"' EXIT HUP INT TERM

api_url="https://api.adoptium.net/v3/assets/latest/21/hotspot?architecture=$architecture&heap_size=normal&image_type=jdk&jvm_impl=hotspot&os=$os&vendor=eclipse"
printf '%s\n' "Tabby: no installed Java 21 found; downloading a SHA-256-verified Eclipse Temurin JDK 21 into Gradle's cache." >&2
metadata=$(curl -fsSL --retry 3 "$api_url") || {
    printf '%s\n' "Tabby: failed to retrieve Temurin 21 release metadata from Adoptium." >&2
    exit 1
}

download_url=$(printf '%s\n' "$metadata" | awk '
    /"package"[[:space:]]*:/ { in_package=1; next }
    in_package && /"link"[[:space:]]*:/ {
        value=$0
        sub(/^.*"link"[[:space:]]*:[[:space:]]*"/, "", value)
        sub(/".*$/, "", value)
        print value
        exit
    }
')
checksum=$(printf '%s\n' "$metadata" | awk '
    /"package"[[:space:]]*:/ { in_package=1; next }
    in_package && /"checksum"[[:space:]]*:/ {
        value=$0
        sub(/^.*"checksum"[[:space:]]*:[[:space:]]*"/, "", value)
        sub(/".*$/, "", value)
        print value
        exit
    }
')

case "$download_url" in https://*) ;; *)
    printf '%s\n' "Tabby: Adoptium did not return a valid HTTPS JDK download URL." >&2
    exit 1
    ;;
esac
case "$checksum" in
    *[!0-9a-fA-F]*|'')
        printf '%s\n' "Tabby: Adoptium did not return a valid SHA-256 checksum." >&2
        exit 1
        ;;
esac
[ "${#checksum}" -eq 64 ] || {
    printf '%s\n' "Tabby: Adoptium returned a checksum with an unexpected length." >&2
    exit 1
}

archive="$tmp_dir/temurin21.tar.gz"
extract_dir="$tmp_dir/extracted"
mkdir -p "$extract_dir"
curl -fL --retry 3 "$download_url" -o "$archive"

if command -v sha256sum >/dev/null 2>&1; then
    printf '%s  %s\n' "$checksum" "$archive" | sha256sum --check - >/dev/null
elif command -v shasum >/dev/null 2>&1; then
    printf '%s  %s\n' "$checksum" "$archive" | shasum -a 256 --check - >/dev/null
else
    printf '%s\n' "Tabby: neither sha256sum nor shasum is available; refusing to install an unverified JDK." >&2
    exit 1
fi

tar -xzf "$archive" -C "$extract_dir"
java_path=$(find "$extract_dir" -type f -path '*/bin/java' -print -quit)
[ -n "$java_path" ] || {
    printf '%s\n' "Tabby: the downloaded archive did not contain a JDK java executable." >&2
    exit 1
}
jdk_root=${java_path%/bin/java}

# Replace only Tabby's own cache directory, never an arbitrary user JDK.
rm -rf "$cached_jdk"
mv "$jdk_root" "$cached_jdk"
if ! is_java21_home "$cached_jdk"; then
    printf '%s\n' "Tabby: the downloaded JDK failed the Java 21 verification." >&2
    rm -rf "$cached_jdk"
    exit 1
fi

printf '%s\n' "$cached_jdk"
