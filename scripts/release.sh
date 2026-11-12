#!/usr/bin/env bash
# Releases a new version: bumps versionName (semver) and versionCode, writes the changelog, then
# commits, tags v<versionName> and pushes. CI builds the signed APK and publishes the GitHub release.
#
# Usage: scripts/release.sh major|minor|patch|<x.y.z>
set -euo pipefail
cd "$(dirname "$0")/.."

gradle=app/build.gradle.kts
die() { echo "$*" >&2; exit 1; }

[ $# -eq 1 ] || die "Usage: $0 major|minor|patch|<x.y.z>"
[ "$(git branch --show-current)" = main ] || die "Not on main"
[ -z "$(git status --porcelain)" ] || die "Uncommitted changes"
git fetch -q origin main
[ "$(git rev-parse HEAD)" = "$(git rev-parse origin/main)" ] || die "main differs from origin/main"

name=$(grep -oP 'versionName = "\K[^"]+' "$gradle")
code=$(grep -oP 'versionCode = \K[0-9]+' "$gradle")
IFS=. read -r major minor patch <<< "$name"
case $1 in
    major) new="$((major + 1)).0.0" ;;
    minor) new="$major.$((minor + 1)).0" ;;
    patch) new="$major.$minor.$((patch + 1))" ;;
    *) [[ $1 =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "Not a version: $1"; new=$1 ;;
esac
[ "$(printf '%s\n' "$name" "$new" | sort -V | tail -1)" = "$new" ] && [ "$new" != "$name" ] ||
    die "$new is not newer than $name"
git rev-parse -q --verify "refs/tags/v$new" > /dev/null && die "Tag v$new exists"
newcode=$((code + 1))

# The changelog starts as the commit subjects since the last release; F-Droid shows at most 500 characters.
changelog=fastlane/metadata/android/en-US/changelogs/$newcode.txt
released=
# Whatever stops the script before the push (an error, an empty changelog, no) leaves no changes behind.
trap '[ -n "$released" ] || { rm -f "$changelog"; git checkout -q -- "$gradle"; }' EXIT
{
    git log --reverse --format='- %s' "v$name..HEAD"
    echo "# Changes in $new for users, at most 500 characters. Lines starting with # are removed."
} > "$changelog"
${VISUAL:-${EDITOR:-vi}} "$changelog" || die "The editor failed, nothing released"
sed -i '/^#/d' "$changelog"
[ -n "$(tr -d '[:space:]' < "$changelog")" ] || die "Empty changelog, nothing released"
[ "$(wc -c < "$changelog")" -le 500 ] || echo "Warning: the changelog is longer than F-Droid shows" >&2

sed -i "s/versionName = \"$name\"/versionName = \"$new\"/; s/versionCode = $code\$/versionCode = $newcode/" "$gradle"
git --no-pager diff --stat
cat "$changelog"
read -rp "Release $new (versionCode $newcode)? [y/N] " ok
[ "$ok" = y ] || die "Nothing released"

git add "$gradle" "$changelog"
git commit -q -m "Release $new"
git tag -a "v$new" -m "v$new"
git push -q --atomic origin main "v$new"
released=1
echo "Pushed v$new. CI publishes https://github.com/Garbaz/android-usb-camera-recorder/releases/tag/v$new"
