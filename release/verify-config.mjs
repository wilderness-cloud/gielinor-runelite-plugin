#!/usr/bin/env node
/*
 * Validates the release configuration without running a release.
 *
 * `semantic-release --dry-run` would be the obvious check, except that it calls
 * verifyAuth unconditionally — a real `git push --dry-run` — even in dry-run
 * mode. That needs a write-capable token, which a job running on pull requests
 * must not have. So this checks the things that actually break instead: the
 * config parsing, every plugin resolving, the local shim, and the two places the
 * release scripts depend on the source's exact shape.
 *
 *   node release/verify-config.mjs
 */
import { spawnSync } from "node:child_process";
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const REPO = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const require = createRequire(join(REPO, "package.json"));

let failures = 0;
const fail = (message) => {
	console.error(`FAIL ${message}`);
	failures++;
};
const ok = (message) => console.log(`ok   ${message}`);

const config = JSON.parse(readFileSync(join(REPO, ".releaserc.json"), "utf8"));
ok(".releaserc.json parses");

if (config.tagFormat !== "v${version}") {
	fail(`tagFormat is ${JSON.stringify(config.tagFormat)}; releases must be tagged v<version>`);
} else {
	ok("tagFormat tags releases as v<version>");
}

if (!Array.isArray(config.branches) || !config.branches.includes("main")) {
	fail(`branches is ${JSON.stringify(config.branches)}; releases must come from main`);
} else {
	ok("releases come from main");
}

// Every plugin has to resolve, or the release fails at merge with the config
// already reviewed and the PR already closed.
for (const entry of config.plugins ?? []) {
	const name = Array.isArray(entry) ? entry[0] : entry;
	try {
		if (name.startsWith(".")) {
			await import(pathToFileURL(join(REPO, name)).href);
		} else {
			require.resolve(name);
		}
		ok(`plugin resolves: ${name}`);
	} catch (error) {
		fail(`plugin does not resolve: ${name} (${error.message})`);
	}

	// A preset is loaded by name at release time, and it is not a dependency of
	// semantic-release itself, so a missing one only shows up after the merge.
	const preset = Array.isArray(entry) ? entry[1]?.preset : undefined;
	if (preset) {
		try {
			await import(`conventional-changelog-${preset}`);
			ok(`release notes preset resolves: ${preset}`);
		} catch (error) {
			fail(`release notes preset does not resolve: ${preset} (${error.message})`);
		}
	}
}

// Resolving is not enough: a preset can load and still fail to render against
// the changelog writer release-notes-generator ships (preset 10 needs writer 9).
// So render notes for a sample commit, offline, with the real config.
const notesEntry = (config.plugins ?? []).find((entry) =>
	(Array.isArray(entry) ? entry[0] : entry) === "@semantic-release/release-notes-generator");
if (notesEntry) {
	try {
		const { generateNotes } = await import("@semantic-release/release-notes-generator");
		const message = "feat: sample feature\n\nA body.";
		const notes = await generateNotes(Array.isArray(notesEntry) ? notesEntry[1] : {}, {
			cwd: REPO,
			env: {},
			logger: { log() {} },
			options: { repositoryUrl: "https://github.com/wilderness-cloud/gielinor-runelite-plugin.git" },
			lastRelease: { gitTag: "v0.0.0", version: "0.0.0" },
			nextRelease: { gitTag: "v0.1.0", version: "0.1.0" },
			commits: [{
				hash: "0123456789abcdef0123456789abcdef01234567",
				message,
				subject: "feat: sample feature",
				body: "A body.",
				commit: { long: "0123456789abcdef0123456789abcdef01234567", short: "0123456" },
				committerDate: new Date().toISOString(),
			}],
		});
		if (!notes.includes("sample feature")) {
			fail(`release notes render, but leave out the sample commit:\n${notes}`);
		} else {
			ok("release notes render for a sample commit");
		}
	} catch (error) {
		fail(`release notes do not render (${error.message})`);
	}
}

// The shim is what turns a PR label into a release type; without analyzeCommits
// semantic-release would silently fall through to commit-message analysis.
try {
	const shim = await import(pathToFileURL(join(REPO, "release/label-release-type.mjs")).href);
	if (typeof shim.analyzeCommits !== "function") {
		fail("release/label-release-type.mjs does not export analyzeCommits");
	} else {
		ok("the label shim exports analyzeCommits");
	}
} catch (error) {
	fail(`cannot load the label shim (${error.message})`);
}

// A syntax error in either script would only surface after the version had
// already been chosen, or the tag already pushed.
for (const script of ["build-jar.sh", "hub-entry.sh"]) {
	const syntax = spawnSync("bash", ["-n", join(REPO, "release", script)], { encoding: "utf8" });
	if (syntax.error) {
		console.log(`skip ${script} syntax check (no bash: ${syntax.error.message})`);
	} else if (syntax.status !== 0) {
		fail(`release/${script} has a syntax error: ${syntax.stderr.trim()}`);
	} else {
		ok(`release/${script} parses`);
	}
}

// build-jar.sh rewrites this exact line with sed. If it is reformatted the
// rewrite silently matches nothing; build-jar.sh would catch that, but only at
// release time.
const pluginJava = readFileSync(
	join(REPO, "src/main/java/cloud/tobbbles/gielinor/runelite/GielinorPlugin.java"), "utf8");
const versionLines = pluginJava.split("\n").filter((line) => /^\tstatic final String VERSION = "[^"]*";$/.test(line));
if (versionLines.length !== 1) {
	fail(`GielinorPlugin.java has ${versionLines.length} lines of the form`
		+ ` '\\tstatic final String VERSION = "...";', and build-jar.sh rewrites exactly one`);
} else {
	ok(`GielinorPlugin.VERSION is where build-jar.sh writes it (${versionLines[0].trim()})`);
}

// The release asset must be named gielinor-<version>.jar, which only holds while
// the Gradle project keeps that name.
const settings = readFileSync(join(REPO, "settings.gradle"), "utf8");
if (!/rootProject\.name\s*=\s*'gielinor'/.test(settings)) {
	fail("settings.gradle no longer names the project gielinor, so the release asset filename would change");
} else {
	ok("the Gradle project name matches the release asset filename");
}

console.log(failures === 0 ? "\nrelease config is valid" : `\n${failures} problem(s)`);
process.exit(failures === 0 ? 0 : 1);
