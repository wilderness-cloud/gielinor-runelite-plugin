# Contributing

Read [`AGENTS.md`](AGENTS.md) and [`docs/design.md`](docs/design.md) first: they record what
RuneLite's Plugin Hub allows and why the plugin is shaped the way it is.

## Versioning: label the PR

This repo releases on merge. Every PR needs **exactly one** of these labels, and the label
decides the version bump:

| Label | Bump | Use it for |
|---|---|---|
| `patch` | 1.4.2 → 1.4.3 | Fixes, docs, internals: nothing the relay or a player has to react to |
| `minor` | 1.4.2 → 1.5.0 | New data sent, new settings, new panel features |
| `major` | 1.4.2 → 2.0.0 | Anything that breaks what's already there (see below) |

CI fails a PR with no version label, or with more than one. That check is there because merging
is what cuts the release: an unlabelled merge ships nothing, and by then the PR is closed.

**What counts as breaking here:** a renamed config key or group without a migration (it silently
resets players' settings), dropping data the Gielinor app relies on, or anything that makes an
existing link stop working. A wire contract change lands in `wilderness-cloud/gielinor` first,
and is breaking only if it is breaking there.

Write the PR title as a [Conventional Commit](https://www.conventionalcommits.org/)
(`fix: resend the bank after a world hop`). Merges are squashed, so the title becomes the commit
subject, and that is what the release notes and CHANGELOG are generated from. A title that
doesn't parse still releases; it just won't appear in the notes.

## What happens on merge

`.github/workflows/release.yml` runs [semantic-release](https://semantic-release.gitbook.io/),
which:

1. reads the release type from the merged PR's label (`release/label-release-type.mjs` is the
   one custom piece: semantic-release reads commit messages by default, and this maps our label
   convention onto it);
2. runs `release/build-jar.sh`, which writes the new version into `GielinorPlugin.VERSION`, then
   tests (with the coverage gate) and builds the jar at that version and checksums it;
3. commits the bump and `CHANGELOG.md` back to `main`, and tags it `v<version>`;
4. publishes the GitHub release with `gielinor-<version>.jar` and its `.sha256` attached;
5. runs `release/hub-entry.sh`, which puts the Plugin Hub entry for the tagged commit in the
   run's summary.

**`GielinorPlugin.VERSION` is the only copy of the version.** The plugin sends it to the relay
as `PluginBuild.version`, and it lives in Java source because the Hub's `build=standard`
replaces our `build.gradle`. Don't add a second one; the jar's filename gets it from
`-Pversion`.

**Publishing to the Hub is still by hand.** The Hub builds a pinned commit of this repository,
so after a release open a PR to [`runelite/plugin-hub`](https://github.com/runelite/plugin-hub)
that replaces `plugins/gielinor` with the entry from the release run's summary. Its `commit=` is
the release commit, whose `VERSION` matches the tag.

If a release run fails partway, or a PR got merged without a label, re-run the release by hand:
**Actions → release → Run workflow**, and pick the bump.

## What CI checks

`.github/workflows/ci.yml` runs on every PR, on `main`, and weekly:

- **plugin (Java 11):** `scripts/coverage.sh` and the jar, uploaded so a reviewer can sideload
  the exact build. The weekly run is there because `build.gradle` builds against RuneLite's
  `latest.release`: it catches the day RuneLite moves its client, or the protobuf runtime it
  ships, without a commit here.
- **generated code is current:** reruns `scripts/generate.sh` with buf 1.73.0 and fails on any
  diff, so the generated code is never edited by hand or left behind a pin bump.
- **exec bits:** `gradlew` and the scripts must be executable in git, or they exit 126.
- **version label** and **release config**, as above.

## Running the checks locally

```
scripts/coverage.sh                  # tests and the 90% coverage gate
scripts/generate.sh && git status    # generated code matches buf.gen.yaml's pin (buf 1.73.0)
npm ci && npm run verify:release     # release config and the label shim (Node 22)
./gradlew run                        # the developer client, to try it in game
```

`npm run release:dry` dry-runs semantic-release itself. It still calls `verifyAuth` (a real
`git push --dry-run`), so it needs push rights and a `GITHUB_TOKEN`. That is also why CI checks
the config with `verify:release` rather than a dry run: a job running on pull requests must not
hold a write-capable token.
