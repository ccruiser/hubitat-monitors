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
