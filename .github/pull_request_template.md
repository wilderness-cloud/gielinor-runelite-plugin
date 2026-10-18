<!--
Label this PR `major`, `minor` or `patch` — exactly one. Merging cuts the
release, and the label picks the version bump. CI fails without it.
See CONTRIBUTING.md.

Title it as a Conventional Commit, e.g. "fix: resend the bank after a world
hop". Merges are squashed, so the title becomes the release note.
-->

## What this changes

## Why

## Checked

- [ ] `scripts/coverage.sh` (tests, and the 90% line and branch gate)
- [ ] `scripts/generate.sh` and committed the result (if `buf.gen.yaml`'s pin moved)
- [ ] Tried in game with `./gradlew run` (if behaviour changed)
- [ ] Still push only, the player's own data, and nothing before they link and switch sending on
