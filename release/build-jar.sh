#!/usr/bin/env bash
#
# semantic-release `prepare` step: stamp the version semantic-release chose into
# GielinorPlugin.VERSION, then build and test the jar attached to the release.
#
# GielinorPlugin.VERSION is the single source of the version: the plugin sends it
# to the relay as PluginBuild.version, and it has to live in Java source because
# the Hub's standard build replaces our build.gradle. The jar's filename takes it
# from -Pversion, so build.gradle holds no copy of its own.
#
#   release/build-jar.sh 0.3.0
#
set -euo pipefail

version="${1:?usage: build-jar.sh <version>}"
repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
plugin_java="src/main/java/cloud/tobbbles/gielinor/runelite/GielinorPlugin.java"
jar_name="gielinor-${version}.jar"
libs="${repo}/build/libs"

cd "$repo"

sed -i.bak -E "s/^(\tstatic final String VERSION = )\"[^\"]*\";\$/\1\"${version}\";/" "$plugin_java"
rm -f "${plugin_java}.bak"
grep -qxF "	static final String VERSION = \"${version}\";" "$plugin_java" || {
	echo "failed to write version ${version} into ${plugin_java}" >&2
	exit 1
}

# Tests and the coverage gate run here too: a release must never ship a jar that
# main's CI would have rejected, and this is the only build whose artifact
# reaches users.
./gradlew --no-daemon clean
scripts/coverage.sh --no-daemon -Pversion="${version}"
./gradlew --no-daemon -Pversion="${version}" jar

# The release asset must be exactly this filename, so check it rather than
# globbing and hoping: a stale jar in build/libs would otherwise be attachable.
built=$(find "$libs" -maxdepth 1 -name 'gielinor-*.jar' -printf '%f\n' | sort)
if [ "$built" != "$jar_name" ]; then
	echo "expected exactly one jar named ${jar_name} in ${libs}, found:" >&2
	echo "${built:-(none)}" >&2
	exit 1
fi

# Checksum with a bare filename inside it, so `sha256sum -c` works wherever the
# file is downloaded to.
(cd "$libs" && sha256sum "$jar_name" > "${jar_name}.sha256")

echo "built ${jar_name}"
cat "${libs}/${jar_name}.sha256"
