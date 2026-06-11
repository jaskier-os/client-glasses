#!/usr/bin/env bash
# Two-way sync between GitLab (canonical) and GitHub for this repo.
# Runs in GitLab CI (shell runner). Idempotent: equal SHAs -> no-op.
# Repo-agnostic: GH_REPO/GL_PATH are derived from CI_PROJECT_PATH, so this same
# script works unchanged in any mirrored project.
#
# Behaviour:
#   - github == gitlab            -> nothing to do.
#   - gitlab ahead (github anc.)  -> fast-forward github to gitlab.
#   - github ahead (gitlab anc.)  -> fast-forward gitlab to github.
#   - diverged, clean merge       -> merge github into gitlab, push BOTH.
#   - diverged, conflict          -> open a GitHub PR (sync/from-gitlab-<sha>),
#                                    user resolves on GitHub; merge flows back next run.
#
# Required env (GitLab CI variables): GITHUB_TOKEN, GITLAB_PUSH_TOKEN.
# Provided by GitLab CI: CI_SERVER_HOST, CI_SERVER_PORT, CI_PROJECT_PATH, CI_DEFAULT_BRANCH.
set -euo pipefail

BRANCH="${CI_DEFAULT_BRANCH:-main}"
GL_PATH="${CI_PROJECT_PATH:?CI_PROJECT_PATH not set}"   # e.g. jaskier-os/client-glasses
GH_REPO="$GL_PATH"                                       # GitHub mirror uses the same org/name
GH_OWNER="${GH_REPO%%/*}"
# CI_SERVER_HOST is portless; include CI_SERVER_PORT (GitLab listens on 4443 here).
GL_HOST="${CI_SERVER_HOST:-10.29.71.1}:${CI_SERVER_PORT:-4443}"

export GIT_SSL_NO_VERIFY=1   # self-signed GitLab
git config --global user.email "sync-bot@jaskier-os"
git config --global user.name  "gitlab-github-sync"

GL_URL="https://oauth2:${GITLAB_PUSH_TOKEN}@${GL_HOST}/${GL_PATH}.git"
GH_URL="https://x-access-token:${GITHUB_TOKEN}@github.com/${GH_REPO}.git"

# Cheap pre-check: compare the two main SHAs via ls-remote (no clone).
GL_HEAD="$(git ls-remote "$GL_URL" "refs/heads/${BRANCH}" | awk '{print $1}')"
GH_HEAD="$(git ls-remote "$GH_URL" "refs/heads/${BRANCH}" | awk '{print $1}')"
echo "ls-remote: gitlab=${GL_HEAD:-none}  github=${GH_HEAD:-none}"
if [ -n "$GL_HEAD" ] && [ "$GL_HEAD" = "$GH_HEAD" ]; then
  echo "already in sync (ls-remote match); skipping full reconcile."
  exit 0
fi

work="$(mktemp -d)"; trap 'rm -rf "$work"' EXIT
git clone -q "$GL_URL" "$work"
cd "$work"
git remote add github "$GH_URL"
git fetch -q origin "$BRANCH"
git checkout -q -B "$BRANCH" "origin/${BRANCH}"
GL="$(git rev-parse "origin/${BRANCH}")"

# First run: GitHub has no main branch yet (ls-remote returned nothing) ->
# just seed it from GitLab. Avoids fetching a ref that doesn't exist.
if [ -z "$GH_HEAD" ]; then
  echo "github main absent -> initial push (seed)."
  git push -q github "${BRANCH}:${BRANCH}"
  echo "github seeded."; exit 0
fi

git fetch -q github "$BRANCH"
GH="$(git rev-parse "github/${BRANCH}")"
echo "gitlab=$GL  github=$GH"

if [ "$GL" = "$GH" ]; then
  echo "in sync; nothing to do."; exit 0
fi

if git merge-base --is-ancestor "$GH" "$GL"; then
  echo "gitlab ahead -> fast-forward github."
  git push -q github "${BRANCH}:${BRANCH}"
  echo "github updated."; exit 0
fi

if git merge-base --is-ancestor "$GL" "$GH"; then
  echo "github ahead -> fast-forward gitlab."
  git merge -q --ff-only "github/${BRANCH}"
  git push -q origin "${BRANCH}:${BRANCH}"
  echo "gitlab updated."; exit 0
fi

echo "diverged -> attempting merge of github into gitlab."
if git merge -q --no-edit "github/${BRANCH}"; then
  echo "clean merge; pushing both."
  git push -q origin "${BRANCH}:${BRANCH}"
  git push -q github "${BRANCH}:${BRANCH}"
  echo "both in sync."; exit 0
fi

echo "merge conflict -> opening GitHub PR for resolution."
git merge --abort
PRB="sync/from-gitlab-$(git rev-parse --short origin/${BRANCH})"
git checkout -q -B "$PRB" "origin/${BRANCH}"
git push -q -f github "${PRB}:${PRB}"

api="https://api.github.com/repos/${GH_REPO}"
existing="$(curl -fsS -H "Authorization: token ${GITHUB_TOKEN}" \
  "${api}/pulls?state=open&head=${GH_OWNER}:${PRB}" | grep -c '"number"' || true)"
if [ "${existing:-0}" -gt 0 ]; then
  echo "PR for ${PRB} already open."; exit 0
fi
body='{"title":"Sync conflict: GitLab changes need resolution","head":"'"${PRB}"'","base":"'"${BRANCH}"'","body":"Automated sync could not fast-forward/merge GitLab `main` into GitHub `main` because both sides changed overlapping lines. Resolve here (merge this PR after fixing conflicts); the merged result syncs back to GitLab automatically."}'
curl -fsS -X POST -H "Authorization: token ${GITHUB_TOKEN}" \
  -H "Accept: application/vnd.github+json" "${api}/pulls" -d "$body" \
  | grep -oE '"html_url": *"[^"]*pull/[0-9]+"' | head -1 || true
echo "conflict PR opened on GitHub."
exit 0
