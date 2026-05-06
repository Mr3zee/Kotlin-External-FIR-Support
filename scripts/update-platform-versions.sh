#!/bin/bash
#
# Bumps EAP-CANDIDATE platformVersion entries in gradle.properties to the latest
# matching candidate from the JetBrains snapshots maven-metadata.xml.
#
# Stable entries (no -EAP-CANDIDATE suffix) are left alone — they don't expire,
# and bumping them pulls in compatibility changes that should be deliberate.
#
# The top-level platformVersion is updated to match ide.<pluginSinceBuild>.platformVersion
# only when that entry is itself an EAP-CANDIDATE.
#
# Usage:
#   ./scripts/update-platform-versions.sh
#   DRY_RUN=1 ./scripts/update-platform-versions.sh

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
GRADLE_PROPERTIES="$PROJECT_DIR/gradle.properties"

SNAPSHOTS_URL="https://www.jetbrains.com/intellij-repository/snapshots/com/jetbrains/intellij/idea/ideaIU/maven-metadata.xml"

fetch_versions() {
    curl -sLf "$1" | grep -oE '<version>[^<]+</version>' | sed -E 's|</?version>||g'
}

echo "Fetching IntelliJ snapshot metadata..."
snapshots_xml=$(fetch_versions "$SNAPSHOTS_URL")

if [[ -z "$snapshots_xml" ]]; then
    echo "Error: Failed to fetch maven-metadata.xml" >&2
    exit 1
fi

# Pick the highest <major>.X.Y-EAP-CANDIDATE from the snapshots feed.
# sort -n stops at the first non-digit, so the EAP suffix doesn't break the sort.
latest_eap_for_major() {
    local major=$1
    echo "$snapshots_xml" | grep -E "^${major}\.[0-9]+\.[0-9]+-EAP-CANDIDATE$" | \
        sort -t '.' -k1,1n -k2,2n -k3,3n | tail -1
}

read_property() {
    grep -E "^${1}[[:space:]]*=" "$GRADLE_PROPERTIES" | \
        sed -E 's/^[^=]*=[[:space:]]*//' | tr -d '[:space:]'
}

PLUGIN_SINCE_BUILD=$(read_property pluginSinceBuild)

PLAN_FILE=$(mktemp)
trap 'rm -f "$PLAN_FILE"' EXIT

echo ""
echo "Plan:"
while IFS= read -r line; do
    if [[ "$line" =~ ^ide\.([0-9]+)\.platformVersion[[:space:]]*=[[:space:]]*(.+)$ ]]; then
        major="${BASH_REMATCH[1]}"
        current=$(echo "${BASH_REMATCH[2]}" | tr -d '[:space:]')
        if [[ "$current" != *-EAP-CANDIDATE ]]; then
            echo "  ide.$major.platformVersion: $current (stable, skipped)"
            continue
        fi
        latest=$(latest_eap_for_major "$major")
        if [[ -z "$latest" ]]; then
            echo "  ide.$major.platformVersion: WARN no EAP-CANDIDATE found, skipping"
            continue
        fi
        if [[ "$current" == "$latest" ]]; then
            echo "  ide.$major.platformVersion: $current (up to date)"
        else
            echo "  ide.$major.platformVersion: $current -> $latest"
        fi
        echo "$major $latest" >> "$PLAN_FILE"
    fi
done < "$GRADLE_PROPERTIES"

TOP_LEVEL_TARGET=""
if [[ -n "$PLUGIN_SINCE_BUILD" ]]; then
    TOP_LEVEL_TARGET=$(awk -v m="$PLUGIN_SINCE_BUILD" '$1==m {print $2}' "$PLAN_FILE")
fi
if [[ -n "$TOP_LEVEL_TARGET" ]]; then
    current_top=$(read_property platformVersion)
    if [[ "$current_top" == "$TOP_LEVEL_TARGET" ]]; then
        echo "  platformVersion: $current_top (up to date)"
    else
        echo "  platformVersion: $current_top -> $TOP_LEVEL_TARGET"
    fi
fi

if [[ -n "$DRY_RUN" ]]; then
    echo ""
    echo "DRY_RUN set; no changes written."
    exit 0
fi

echo ""
echo "Applying changes..."
while IFS=' ' read -r major latest; do
    [[ -z "$major" ]] && continue
    perl -i -pe "s|^(ide\\.${major}\\.platformVersion[[:space:]]*=[[:space:]]*).+\$|\${1}${latest}|" "$GRADLE_PROPERTIES"
done < "$PLAN_FILE"

if [[ -n "$TOP_LEVEL_TARGET" ]]; then
    perl -i -pe "s|^(platformVersion[[:space:]]*=[[:space:]]*).+\$|\${1}${TOP_LEVEL_TARGET}|" "$GRADLE_PROPERTIES"
fi

echo "Done."
