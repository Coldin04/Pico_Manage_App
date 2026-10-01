#!/usr/bin/env bash
set -euo pipefail

usage() {
    printf 'Usage: %s <sdk-version-with-git-sha>\n' "$0" >&2
    printf 'Example: %s git.<40-character-commit-sha>\n' "$0" >&2
}

if [[ $# -ne 1 ]]; then
    usage
    exit 2
fi

sdk_version="$1"
if [[ ! "$sdk_version" =~ ^git\.([0-9a-f]{40})$ ]]; then
    printf 'Expected git.<full-commit-sha>, got: %s\n' "$sdk_version" >&2
    exit 2
fi
commit_sha="${BASH_REMATCH[1]}"
sdk_repository="${INKREADERLINK_SDK_REPOSITORY:-https://github.com/Coldin04/InkReaderLink.git}"

if ! command -v git >/dev/null 2>&1; then
    printf '%s\n' 'Git is required to build an SDK git version.' >&2
    exit 1
fi

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
if [[ -n "${GRADLE_BIN:-}" ]]; then
    if [[ -x "$GRADLE_BIN" ]]; then
        gradle_cmd=("$GRADLE_BIN")
    elif command -v "$GRADLE_BIN" >/dev/null 2>&1; then
        gradle_cmd=("$GRADLE_BIN")
    else
        printf 'Gradle executable not found: %s\n' "$GRADLE_BIN" >&2
        exit 1
    fi
elif [[ -x "$repo_root/android/gradlew" ]]; then
    gradle_cmd=("$repo_root/android/gradlew")
elif command -v gradle >/dev/null 2>&1; then
    gradle_cmd=(gradle)
else
    printf '%s\n' 'Gradle is required (set GRADLE_BIN to an installed Gradle or wrapper).' >&2
    exit 1
fi

temp_root="$(mktemp -d "${TMPDIR:-/tmp}/inkreaderlink-sdk-git.XXXXXX")"
cleanup() {
    rm -rf "$temp_root"
}
trap cleanup EXIT

sdk_checkout="$temp_root/sdk"
git init -q "$sdk_checkout"
git -C "$sdk_checkout" remote add origin "$sdk_repository"
git -C "$sdk_checkout" fetch --quiet --depth=1 origin "$commit_sha"
git -C "$sdk_checkout" checkout --quiet --detach FETCH_HEAD

resolved_sha="$(git -C "$sdk_checkout" rev-parse HEAD)"
if [[ "$resolved_sha" != "$commit_sha" ]]; then
    printf 'Requested SDK commit %s but fetched %s\n' "$commit_sha" "$resolved_sha" >&2
    exit 1
fi

bash "$sdk_checkout/scripts/build-android-sdk.sh"
"${gradle_cmd[@]}" -p "$sdk_checkout/android-sdk" publishToMavenLocal "-PsdkVersion=$sdk_version"

printf '\nInstalled com.cold04:inkreaderlink-uniffi:%s into mavenLocal().\n' "$sdk_version"
printf 'SDK repository: %s\n' "$sdk_repository"
printf 'SDK commit: %s\n' "$resolved_sha"
