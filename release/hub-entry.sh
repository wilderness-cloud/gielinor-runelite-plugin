#!/usr/bin/env bash
#
# semantic-release `success` step: print the Plugin Hub entry for the release just
# tagged, and put it in the workflow's job summary. Updating the Hub is then a
# pull request to runelite/plugin-hub that replaces plugins/gielinor with it.
#
# The commit is the tag's, which is the version-bump commit semantic-release just
# pushed, not the merge before it: that is the commit whose VERSION matches.
#
#   release/hub-entry.sh 0.3.0
#
set -euo pipefail

version="${1:?usage: hub-entry.sh <version>}"
commit=$(git rev-parse "v${version}^{commit}")

# The install-time warning from docs/design.md ("The Hub warning"). Keep the two
# in step.
warning="This plugin sends your character's state (skills, quests, diaries, bank, inventory and equipment) and your IP address to Gielinor's server, which is not controlled or verified by the RuneLite developers. It is only visible to you, in the Gielinor app."

entry="repository=https://github.com/wilderness-cloud/gielinor-runelite-plugin.git
commit=${commit}
warning=${warning}
authors=tobbbles"

echo "$entry"

if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
	{
		echo "## Plugin Hub entry for v${version}"
		echo
		echo "Replace \`plugins/gielinor\` in [runelite/plugin-hub](https://github.com/runelite/plugin-hub) with:"
		echo
		echo '```'
		echo "$entry"
		echo '```'
	} >> "$GITHUB_STEP_SUMMARY"
fi
