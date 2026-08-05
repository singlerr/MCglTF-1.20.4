#!/usr/bin/env bash
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel 2>/dev/null)" || {
  echo "Run this script inside the MCglTF Git repository." >&2
  exit 1
}
cd "$repo_root"

project_version="$(sed -n 's/^mod_version=//p' gradle.properties | head -n 1)"
version="${1:-$project_version}"
version="${version#v}"

if [[ -z "$project_version" ]]; then
  echo "mod_version is missing from gradle.properties." >&2
  exit 1
fi
if [[ ! "$version" =~ ^[0-9A-Za-z][0-9A-Za-z._+-]*$ ]]; then
  echo "Invalid release version: $version" >&2
  exit 1
fi
if [[ "$version" != "$project_version" ]]; then
  echo "Requested version '$version' does not match mod_version '$project_version'." >&2
  exit 1
fi
if [[ -n "$(git status --porcelain --untracked-files=normal)" ]]; then
  echo "The working tree contains uncommitted changes. Commit them before releasing." >&2
  exit 1
fi

branch="$(git branch --show-current)"
if [[ -z "$branch" ]]; then
  echo "Releases must be created from a branch, not detached HEAD." >&2
  exit 1
fi

upstream="$(git rev-parse --abbrev-ref --symbolic-full-name '@{upstream}' 2>/dev/null)" || {
  echo "Branch '$branch' has no upstream. Push it before releasing." >&2
  exit 1
}
remote="${upstream%%/*}"
remote_branch="${upstream#*/}"
git fetch "$remote" "$remote_branch"

read -r behind ahead < <(git rev-list --left-right --count "$upstream...HEAD")
if [[ "$behind" != "0" || "$ahead" != "0" ]]; then
  echo "HEAD and $upstream differ (behind=$behind, ahead=$ahead). Push or pull before releasing." >&2
  exit 1
fi

tag="v$version"
if git rev-parse --quiet --verify "refs/tags/$tag" >/dev/null; then
  echo "Local tag $tag already exists." >&2
  exit 1
fi
if git ls-remote --exit-code --tags "$remote" "refs/tags/$tag" >/dev/null 2>&1; then
  echo "Remote tag $tag already exists." >&2
  exit 1
else
  status=$?
  if [[ "$status" != "2" ]]; then
    echo "Could not check remote tag $tag (git ls-remote exit $status)." >&2
    exit "$status"
  fi
fi

./gradlew clean build --no-daemon

git tag -a "$tag" -m "Release $tag"
git push "$remote" "refs/tags/$tag"

echo "Pushed $tag. GitHub Actions will build the JARs and create the GitHub Release."
