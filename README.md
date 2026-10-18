# Gielinor RuneLite plugin

A RuneLite plugin that sends your character's state to [Gielinor](https://gielinor.tobbbles.cloud),
so the Gielinor desktop app can track your goals against your real account: skills, quests,
achievement diaries, inventory, equipment and bank.

**Status: in development.** See [`docs/design.md`](docs/design.md). It builds, but does nothing yet.

- **You link it yourself.** It sends nothing until you link it to your Gielinor account from its
  side panel. You approve the link on the Gielinor site, in your own browser, and the plugin
  never asks for a password.
- **Only you see it.** What it sends is visible to you, in the Gielinor app. It is not a public
  profile.
- **You choose what goes.** Each kind of data can be switched off, and unlinking stops sending at
  once.
- **Read-only.** It never clicks, moves or changes anything in game.

The wire format is the protobuf contract in the Gielinor desktop repository (`proto/`),
published as [`buf.build/wilderness-cloud/gielinor`](https://buf.build/wilderness-cloud/gielinor).

## Licence

BSD 2-Clause; see [`LICENSE`](LICENSE).
