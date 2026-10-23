# hubitat-monitors

Hubitat Hub Code Used for Monitoring

Casey Cruise's Hubitat packages, installable with
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

## Development

Every `.groovy` file is compile-checked by GitHub Actions on
push and pull request (`.github/workflows/groovy-syntax.yml`,
stock `groovyc` in a Groovy container). The sources are written
with dynamic typing and no Hubitat platform class references so
they compile standalone; the check catches syntax and structural
errors, not Hubitat runtime behavior.

Versions live in each package's `packageManifest.json`
(`version` + `releaseNotes`); bump them with any code change so
HPM offers the update.
