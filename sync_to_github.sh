#!/usr/bin/env bash
set -e

REPO_NAME="thinkertomar/Focus-Elite"
TOKEN="${GITHUB_PAT:-$1}"

echo "=== Focus Elite GitHub Sync ==="

# Stage and commit any pending changes
git add -A .gitignore metadata.json settings.gradle.kts build.gradle.kts gradle.properties gradle/ gradlew gradlew.bat app/ .github/
if git diff-index --quiet HEAD --; then
    echo "All current changes are already committed on branch main."
else
    git commit -m "Focus Elite: Latest update with dark icon and calisthenics engine"
    echo "Committed latest changes to local main."
fi

if [ -n "$TOKEN" ]; then
    echo "Pushing to https://github.com/${REPO_NAME}.git ..."
    git push "https://${TOKEN}@github.com/${REPO_NAME}.git" main
    echo "=== Push successful! Changes are live on https://github.com/${REPO_NAME} ==="
else
    echo ""
    echo "Notice: To sync via terminal, provide your GitHub Personal Access Token (PAT):"
    echo "  ./sync_to_github.sh <YOUR_PAT>"
    echo "  or: export GITHUB_PAT=<YOUR_PAT> && ./sync_to_github.sh"
    echo ""
    echo "Alternatively, use the AI Studio 'Sync to GitHub' button in the top menu bar."
fi
