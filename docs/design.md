# The Gielinor RuneLite plugin: design

Status: **design** (18 Oct 2026). No code yet.

The Gielinor plugin is a RuneLite plugin, published on the Plugin Hub. It sends a player's
character state to the RuneLite relay, and the desktop app reads it back from there. It
replaces the desktop's dependency on the sideloaded `runelite-mcp-server-plugin`.

## Decisions

- **A new plugin, in its own repository: this one, `wilderness-cloud/gielinor-runelite-plugin`.**
  The Hub builds one repository from its root, at a pinned commit, and has no field for a
  subdirectory. So the plugin's repository is the one the Hub builds, with no mirror in between.
  - **The wire contract stays in the desktop's repository** (`wilderness-cloud/gielinor`,
    `proto/`), and is published to the Buf Schema Registry as `buf.build/wilderness-cloud/gielinor`.
    This repository generates its Java from a pinned registry commit, as the relay does.
  - A contract change therefore lands in `gielinor` first, then here as a pin bump plus the code
    that uses it.
- **`wilderness-cloud/runelite-mcp-server-plugin` becomes prior art.** We don't share a library
  with it. We do take ideas from it, re-written for the Hub's rules: its providers, the farming
  tables, the combat achievement and collection log decoding, and "absent is not empty".
  It keeps working for anyone who sideloads it.
- **Push only.** No server, no port, no request ever reaches the game client. This is the shape
  the Hub accepts, and the only one it does: "Plugins which expose player information over
  HTTP" is on RuneLite's rejected-features list.
- **Binary protobuf over Connect, from generated Java.** RuneLite's client already exposes
  `com.google.protobuf:protobuf-javalite:3.21.12` to plugins (`api(libs.protobuf)` in
  `runelite-client/build.gradle.kts`, checked 18 Oct 2026). So generated lite classes add **no
  dependency**, and the Hub's manual check of third-party dependencies doesn't apply.
  - JSON would avoid code generation but can't carry our masks. JSON spells `FieldMask`
    paths in lowerCamelCase, and map-key paths such as `skills.ATTACK` don't survive the
    round trip.
- **Off until linked.** The plugin sends nothing until the player links it from its side
  panel, and says what it will send before they do.

## What RuneLite allows, and what it means here

Sources, read 18 Oct 2026:
- the plugin-hub `README.md`;
- the RuneLite wiki pages "Rejected or Rolled Back Features", "Plugin Hub Review" and
  "Information about the Plugin Hub";
- `ConfigItem.java` in the client.

| Rule | What we do |
|---|---|
| Plugins that talk to third-party servers must warn "either on the plugin, or on the configuration option enabling the setting, explaining what data is being sent". | **Both.** The Hub entry carries `warning=` (shown when installing), and the "Send to Gielinor" config item has `warning = …` (shown when it is switched on). WikiSync's wording is the model (see below). |
| Network IO is a "sensitive API": every change to it gets extra review. | Keep the network code in two small classes (`AccountClient`, `RelayClient`), so a reviewer can read all of it. |
| Only OkHttp, and only the client's instance: no `new OkHttpClient()`, no `HttpURLConnection` or `java.net.http`. | Inject `OkHttpClient`. Use `newBuilder()` only to set timeouts. Calls are async (`enqueue`). |
| No `Thread.sleep` or `Thread.interrupt`. | Debounce, heartbeat, polling and backoff run on RuneLite's injected `ScheduledExecutorService`. |
| No reflection, JNI/JNA, subprocesses, or code fetched at runtime. No `java.io` file APIs (use `Filepath`). | None needed. Nothing is written to disk outside RuneLite's config. |
| `Desktop.browse` and `LinkBrowser.open` are forbidden; `LinkBrowser.browse` is allowed. | The sign-in button opens the approval page with `LinkBrowser.browse`. |
| No own `Gson` instance. | Inject the client's `Gson` to read the OAuth server's JSON answers. |
| Java only; the package must not be `net.runelite`. | `cloud.tobbbles.gielinor.runelite`, matching the desktop's identifier. |
| "Credential manager" plugins that store **game** credentials are rejected. | Not this. Nothing about the Jagex account is asked for or stored. The plugin keeps our service's OAuth refresh token (see "Where the token lives"). |
| No crowdsourcing data about **other** players. | The player's own state only. No names, gear or positions of others, and no chat text. |
| New plugins (from 14 Oct 2026) with "significant functionality locked behind paid external services" are prohibited. | Linking needs a Gielinor account, which is free; nothing is sold. **Risk:** the account service is invitation-only today. Submit once anyone can sign up, or ask the reviewers first. |
| The Hub builds **one repository, from its root**, at a pinned commit (`repository=`, `commit=`); there is no subdirectory field. | This repository is the Hub's: `build.gradle` at the root, and the Hub entry pins a commit of it. It must be **public** before submission (private while it's being built). |
| `build=standard` replaces our `build.gradle` with the Hub's; `gradle` keeps ours. | `standard`: we need nothing beyond RuneLite's client, and it gets the quicker review. |
| A `LICENSE` (the Hub suggests BSD 2-Clause); `icon.png` at most 48×72 at the root. | `LICENSE` is BSD-2-Clause. `icon.png` comes with the first release. |
| Reviews are done by people and, since April 2026, an automated bot that can approve simple updates. | Small, plain updates. Keep anything that needs a human (new endpoints, new data) in releases of its own. |

**The Hub warning**, adapted from WikiSync's ("Similar to the official HiScores, this plugin
pushes up data about your character that can be viewed by anyone…"). Ours isn't public, so:

> This plugin sends your character's state (skills, quests, diaries, bank, inventory and
> equipment) and your IP address to Gielinor's server, which is not controlled or verified by
> the RuneLite developers. It is only visible to you, in the Gielinor app.

The config item's warning repeats the first sentence, so the player sees it at the moment they
switch sending on.

### Where the token lives

- **What is stored:** RuneLite config, group `gielinor`, two hidden items:
  - `refreshToken`, with `secret = true`, so it is masked if ever shown;
  - `clientId`, a random id made on first run, one per install (the relay's `client_id`).
- **The access token lives only in memory.** It lasts an hour.
- **Refresh tokens rotate** on every use, with a 90-day life. Each refresh saves the new one
  straight away. A replayed old one is refused, which ends the link (the "Link expired" state
  below).
- **Trade-off:** a player signed in to a RuneLite account has their config synced to
  RuneLite's servers, so the refresh token travels there too. Plugins that keep webhook URLs or
  verification codes in config (Dink, Wise Old Man) already do the same.
- **What a stolen token can do:** it only has `runelite:push` (plus `offline_access`, for the
  refresh). It can write this account's game state, and read nothing. The account site's
  "Linked RuneLite clients" can revoke it.
- **Not chosen:** a file under `RUNELITE_DIR` through `Filepath`, which isn't synced. It would
  mean using `Filepath` and the Hub's file rules for little gain.

## Shape

```
build.gradle, settings.gradle   # the example-plugin template's; runeLiteVersion = 'latest.release'
runelite-plugin.properties      # displayName=Gielinor, build=standard, plugins=cloud.tobbbles.gielinor.runelite.GielinorPlugin
buf.gen.yaml                    # protoc-gen-java v21.12, lite, from buf.build/wilderness-cloud/gielinor (pinned)
LICENSE, README.md, icon.png
src/main/java/cloud/tobbbles/gielinor/runelite/
  GielinorPlugin.java           # wiring: events → collectors → Pusher; panel; lifecycle
  GielinorConfig.java           # what to send, the warning, hidden token items
  panel/GielinorPanel.java      # link / unlink, the code, status, last push
  account/AccountClient.java    # discovery, device authorization, polling, refresh, revoke
  account/Link.java             # the link's state machine, persisted through ConfigManager
  relay/RelayClient.java        # Connect unary POST, binary protobuf, gRPC status mapping
  relay/Pusher.java             # dirty set, debounce, seq, heartbeat, backoff, masks
  state/*Collector.java         # one per domain: game state → CharacterState field + dirty paths
src/main/java/gielinor/runelite/v1/...   # generated (java_package via managed mode), committed
src/test/java/...
```

**Collectors run on the client thread**, because quest states, varbits and item containers must
be read there. Each one builds its slice of `CharacterState` and returns the paths it changed.
The `Pusher` takes those on the client thread, and builds and sends the request off it.

## Linking (the device grant)

1. **The player presses "Link to Gielinor"** in the panel. `AccountClient` reads
   `https://gielinor.tobbbles.cloud/.well-known/oauth-authorization-server` (RFC 8414), then
   posts `client_id=gielinor-runelite`, `scope=runelite:push offline_access` to the
   `device_authorization_endpoint`.
2. **The panel shows the `user_code`** in a monospace font, as the desktop does. A button opens
   `verification_uri_complete` with `LinkBrowser.browse`. The player signs in and approves on
   the site, in their own browser.
3. **The plugin polls** the token endpoint every `interval` seconds, on the scheduler. It
   follows RFC 8628: keep going through `authorization_pending`, add 5 s for good on
   `slow_down`, and stop on `access_denied` or `expired_token`. The device code lasts 15 minutes.
4. **On success** it stores the refresh token, keeps the access token in memory, and switches
   sending on.
   - The panel says "Linked", then shows the character being pushed and the time of the last
     acknowledged push.
5. **Unlink** revokes the refresh token (RFC 7009) and clears both config items.
   - A token revoked from the account site shows as **"Link expired — link again"** the next
     time a refresh fails. So does a refresh token past its 90 days.

**Is this allowed?** Nothing in the rules forbids an OAuth sign-in. The relevant rules are all
followed:
- `LinkBrowser.browse` is the allowed way to open a page.
- No game credentials are involved.
- Network IO goes through the client's OkHttp.

The device grant also means no password ever passes through the game client, and no local
callback port is opened (a redirect-based OAuth flow would need one, and a listening port is
exactly what the Hub rejects).

## Pushing

This follows the relay's design (`docs/runelite-relay.md`, "Plugin → relay", in the closed-source `gielinor-web`). The parts that
matter for the plugin:

- **When.**
  - **Changes:** `StatChanged`, `ItemContainerChanged` (inventory, equipment; the bank when
    its interface closes), `VarbitChanged` for quests and diaries, and `GameStateChanged`.
    Each marks paths dirty, and a push follows about 2 s after the last change (debounced).
  - **Heartbeat:** an empty mask every `next_heartbeat_s` (60 s by default) while logged in.
  - **Logout:** `logged_in: false` is pushed at once.
- **Which character.**
  - `CharacterRef`: `Client.getAccountHash()`, the RSN, the world, and
    `RuneScapeProfileType.getCurrent(client).name()`.
  - BETA and PVP_ARENA profiles push nothing, and neither do tournament worlds (checked by
    world type, since RuneLite files them under STANDARD). A league or Deadman character is
    separate from the main one, as the relay expects.
- **The dirty set is cleared only by an ack.** Each push sends every dirty path with its
  current value, and clears the paths of the push named by `acked_seq`. One push at a time.
- **`seq`** starts at the current time in milliseconds and goes up by one per push, so it keeps
  rising across restarts.
- **Masks in the answer.**
  - `missing` paths are added to the dirty set.
  - `wanted` turns on the expensive collectors (`combat_achievements`, `collection_log`) while
    they're named.
  - `next_heartbeat_s` replaces the heartbeat interval.
- **Errors, by Connect status.**

  | Status | What the plugin does |
  |---|---|
  | `UNAUTHENTICATED` | Refresh once, then retry. If the refresh fails: "Link expired". |
  | `PERMISSION_DENIED` | "Link expired" (wrong scope). |
  | `FAILED_PRECONDITION` | Jump `seq` past `gielinor-last-seq` and push again. |
  | `RESOURCE_EXHAUSTED`, `UNAVAILABLE`, network errors | Back off exponentially (honouring `Retry-After`), keeping the dirty set. |
  | `INVALID_ARGUMENT` | A bug. Log the paths (never the values), drop that push's paths and carry on. |

- **No queue beyond the dirty set.** Offline play costs nothing. The next push carries current
  values.
- **Requests are gzipped** (`Content-Encoding: gzip`; the relay accepts it). A full bank is the
  largest message.

## What the first release sends

These are the fields the desktop's snapshot already uses, so switching the desktop to the
relay (plan item RL7) loses nothing:

| Field | From | When |
|---|---|---|
| `account` (type, members) | `Client.getAccountType()`, world type | login |
| `skills` (level, xp; per entry) | `Client.getRealSkillLevel` / `getSkillExperience` | `StatChanged` |
| `quests` (state; per entry) | `Quest.getState(client)` | login, quest varp changes |
| `diaries` | the diary varbits (`VarbitID.*_DIARY_*_COMPLETE`, as the MCP plugin's `ProgressProvider` reads them) | login, varbit changes |
| `inventory`, `equipment` | `ItemContainerChanged` | change |
| `bank` | bank container, on the bank closing | bank close |

Each group has its own config toggle (on by default once linked). That way a player can keep,
say, their bank to themselves. A toggled-off group is never collected, and its paths are
pushed once **unset**, so the relay forgets them.

Later, each in its own release:
- `combat_achievements` and `collection_log`, built only while `wanted`;
- `boss_kc`, `slayer`, `farming` and `charges`;
- events (loot, level-ups, deaths), for the outbox.

`position` and `vitals` stay out until there's a reason: where a player is right now is the
most sensitive thing the plugin could send.

## Build, test, release

- **Code generation.** `buf generate` writes the lite Java with `protocolbuffers/java`
  **v21.12**, matching RuneLite's runtime, from the registry module pinned in `buf.gen.yaml`.
  The generated code is committed (`build=standard` can't run protoc).
  - CI regenerates it and fails on any diff.
  - A CI job builds against `latest.release`, which catches the day RuneLite moves its protobuf
    version.
- **Tests.**
  - JUnit with a mocked `Client` for the collectors.
  - OkHttp's `MockWebServer` for `AccountClient` and `RelayClient`: the device grant's states,
    refresh rotation, and each status code.
  - A contract test that decodes our pushes with the relay's own schema (round trip through the
    generated classes).
- **Release.** A `v*` tag runs a workflow that builds and tests the tagged commit, then prints
  the Hub entry for it (`plugins/gielinor`: `repository=`, `commit=`, `warning=`, `authors=`).
  Updating the Hub is then a one-line pull request to `runelite/plugin-hub`.
- **Running it locally** uses the template's `run` task (developer mode). A Jagex account needs
  the developer client's account guide, linked from the Hub README.

## Open questions

1. **When to submit.** The paid-services rule plus an invitation-only account service could
   read as gated. Submit when sign-up is open, or ask the reviewers first?
2. **Hub name.** `gielinor` is free on the Hub. `gielinor-profile-sync` exists but is an
   unrelated, disabled plugin. Display name "Gielinor"?
3. **The token in synced config** (see "Where the token lives"). Keep it, or use a local file?
4. **Going public.** This repository is private while it's built. It has to be public before
   the Hub submission, and the desktop repository's licence question (it has none yet) doesn't
   block it: this one is BSD-2-Clause on its own.
