# hubitat-monitors

Hubitat Hub Code Used for Monitoring

Hubitat packages, installable with
[Hubitat Package Manager](https://hubitatcommunity.com/) (HPM).

## Adding this repository to HPM

In HPM: **Package Manager Settings → Custom repositories → Add a
Custom Repository**, and paste the raw URL of this repo's
`repository.json`:

```
https://raw.githubusercontent.com/ccruiser/hubitat-monitors/refs/heads/main/repository.json
```

Then use HPM's **Install** to browse and install packages.

## Packages

### Plumcrest Cloud Backup Reporter (`cloud-backup-reporter/`)

Reports the hub's cloud backup result where a Maker API caller
can read it, even with Hub Login Security enabled.

Why it exists: `/hub2/hubData` carries the last cloud backup
message, but on a security-enabled hub a LAN client calling it
unauthenticated is redirected to the login page. Code running on
the hub is inside that gate, so:

- the **Writer app** subscribes to the hub's built-in
  `cloudBackup` location event (pattern after thebearmay's
  community example), and
- writes each result into the **Reporter virtual device**
  (`cloudBackupStatus`, `cloudBackupDetail`, `lastCloudBackup`,
  `cloudBackupCount`).

Setup after HPM installs the package:

1. Devices → Add virtual device → type **Plumcrest Cloud Backup
   Reporter** (namespace `plumcrest`), name it e.g. "Cloud Backup
   Reporter".
2. Apps → open **Plumcrest Cloud Backup Writer** → select that
   device → Done.
3. To consume it off-hub: authorize the Reporter device in a
   Maker API instance; the attributes ride the normal Maker
   device payload.

Attributes stay `unknown` until the first cloud backup after
install (backups run nightly). The device page's
`setCloudBackup` command can be run by hand to test consumers.

### Plumcrest Hub Updater (`hub-updater/`) — SPIKE

Gives platform updates the fleet's upgrader shape (detect →
propose → human go → apply → verify) using the hub's own internal
update endpoints, reverse-engineered from the Check for Updates
page: `/hub/cloud/checkForUpdate`,
`/hub/cloud/updatePlatform?...`, `/hub/cloud/checkUpdateStatus`,
`/hub/cloud/clearUpdateStatus`.

Commands (human-invoked only; nothing is scheduled):
`checkForUpdate`, `refreshUpdateStatus`, `clearUpdateStatus`,
and `applyUpdate`. Apply is double-gated: the `enableApply`
preference is a **timed arm** (`applyArmMinutes`, default 60)
that the driver switches off itself when the window closes,
after a successful apply, or at boot if expired — and apply
only ever applies the exact version a prior check offered.
Maker API can invoke the commands but **cannot arm** (Maker
cannot set preferences), so authorizing the device never hands
over an armed apply.

Proven live (Living Room hub, Oct 23, 2026): detection leg over
`http://127.0.0.1:8080`; arm timer set, fired, and self-disarmed.
Unproven: a real apply (needs an update pending) and any call on
a Hub Login Security enabled hub.

Setup: Devices → Add virtual device → type **Plumcrest Hub
Updater** (namespace `plumcrest`). Optionally authorize it in a
Maker API instance to read `updateStatus` / `latestVersion` /
`lastResult` off-hub.

### Plumcrest Hub Backup (`hub-backup/`)

Pushes the hub's newest local backup off the hub to the Util2
receiver, which stores it on the NAS (plumnas
`home_lab_backups/hubitat`) — an off-hub copy of the hub
database that survives the hub itself.

Why it exists: with Hub Login Security enabled there is no
supported way for an off-hub puller to authenticate (the hub
allows a single local admin; a dedicated backup user is
impossible), so the flow is a push from code running on the
hub:

- the driver logs in to the hub's own **Diagnostic Tool API**
  (`127.0.0.1:8081`) with the hub MAC — the diag service's own
  protocol; no platform credentials are stored or used,
- downloads the newest store backup (normally the night's
  scheduled 3:01 AM backup) into memory only — the bytes
  never enter an attribute or state,
- POSTs them to the token-gated receiver on Util2, and
  counts the run `PUSH_OK` only when the receiver reports
  storing exactly the bytes held; its SHA-256 lands in the
  `lastSha256` attribute.

Commands: `backupNow` (the nightly motion) and
`createAndPushBackup` (fresh backup via the platform create
URL with a 60-second timeout, plus a list/download fallback
when the create lands but returns no bytes). Trigger it from
a **dedicated** Maker API instance (never the collector's),
Rule Machine, or the driver's optional built-in nightly
schedule (default off; a scheduled failure retries once
after 15 minutes). Status rides the attributes: `lastResult`,
`health`, `lastFile`, `lastBytes`, `lastSha256`,
`backupCount`, `newestBackup`.

Setup after HPM installs the package:

1. Devices → Add virtual device → type **Plumcrest Hub
   Backup** (namespace `plumcrest`), name it e.g. "Hub
   Backup".
2. Preferences: this hub's MAC (Settings → Hub Details), the
   receiver base URL, and the receiver token.
3. Optionally authorize the device in its own Maker API
   instance to trigger `backupNow` and read status off-hub.

Proven live (Game Room C-8, platform 2.5.2.139, Hub Login
Security on, Oct 30, 2026): `backupNow` fetched the day's
scheduled backup (3,183,312 bytes); the receiver stored it
byte-for-byte (SHA-256 verified). Unproven: the Living Room
hub (rollout planned from this repo) and the built-in
schedule route.

## Development

Every `.groovy` file is checked by GitHub Actions on push and
pull request (`.github/workflows/main.yml`, Groovy container):

1. **Compile check** — stock `groovyc`. The sources are written
   with dynamic typing and no Hubitat platform class references
   so they compile standalone.
2. **Sandbox lint** — `ci/HubitatLint.groovy` compiles each
   hub-bound source through a `SecureASTCustomizer` configured
   with Hubitat's known restrictions. This exists because
   `groovyc` passed `System.currentTimeMillis()` and
   `URLEncoder` calls that the hub's sandbox rejected on save
   (Hub Updater v0.2.0, Oct 23, 2026); the lint reproduces that
   class of verdict locally and in CI. It is an approximation
   of Hubitat's whitelist, not the hub itself — a pass means
   "no known violation."

Neither check validates Hubitat runtime behavior; only the hub
can do that.

Versions live in each package's `packageManifest.json`
(`version` + `releaseNotes`); bump them with any code change so
HPM offers the update.
