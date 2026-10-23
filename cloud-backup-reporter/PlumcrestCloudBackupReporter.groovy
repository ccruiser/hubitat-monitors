/*
 * Plumcrest Cloud Backup Reporter (virtual device driver)
 *
 * Casey Cruise / Muse, Oct 22, 2026. Version 1.0.0.
 *
 * Purpose: hold the hub's cloud backup result where token-based
 * callers (Maker API) can read it. The hub's /hub2/hubData carries
 * the last cloud backup message, but on a hub with Hub Login
 * Security enabled a LAN client calling hubData unauthenticated is
 * redirected to /login. Code running ON the hub is inside that
 * gate, so the companion app (Plumcrest Cloud Backup Writer)
 * listens for the hub's built-in "cloudBackup" location event and
 * writes each result into this device via the setCloudBackup
 * command. Authorize this device in a Maker API instance and the
 * result is readable with the Maker token - no hub UI credentials
 * anywhere.
 *
 * This driver is intentionally passive: it never polls anything.
 * Its attributes stay "unknown" until the first cloudBackup event
 * after install (cloud backups run nightly, so it populates within
 * about a day).
 *
 * Written with dynamic typing throughout (no Hubitat class
 * references) so a stock groovyc can syntax/compile-check it in
 * CI - see .github/workflows/groovy-syntax.yml in this repo.
 */

metadata {
    definition(
        name: "Plumcrest Cloud Backup Reporter",
        namespace: "plumcrest",
        author: "Casey Cruise"
    ) {
        capability "Sensor"
        capability "Actuator"

        attribute "cloudBackupStatus", "string"   // success | failed | unknown
        attribute "cloudBackupDetail", "string"   // event description text
        attribute "lastCloudBackup", "string"     // local time of last event
        attribute "cloudBackupCount", "number"    // events seen since install

        command "setCloudBackup", [
            [name: "status", type: "STRING", description: "success or failed"],
            [name: "detail", type: "STRING", description: "Event description text"]
        ]
        command "clearCloudBackup"
    }

    preferences {
        input name: "logEnable", type: "bool",
            title: "Enable debug logging", defaultValue: false
    }
}

def installed() {
    log.info "${device.label}: installed"
    initialize()
}

def updated() {
    log.info "${device.label}: preferences updated, debug logging is ${logEnable == true}"
    if (logEnable) runIn(1800, "logsOff")
    initialize()
}

def initialize() {
    if (device.currentValue("cloudBackupStatus") == null) {
        sendEvent(name: "cloudBackupStatus", value: "unknown",
            descriptionText: "No cloud backup event seen yet")
    }
    if (device.currentValue("cloudBackupCount") == null) {
        sendEvent(name: "cloudBackupCount", value: 0)
    }
}

def logsOff() {
    log.warn "${device.label}: debug logging disabled automatically"
    device.updateSetting("logEnable", [value: "false", type: "bool"])
}

// Called by the Plumcrest Cloud Backup Writer app on every
// cloudBackup location event. Also safe to call by hand from the
// device page (Commands tab) to test downstream consumers.
def setCloudBackup(status, detail) {
    def normalized = (status ?: "unknown").toString().toLowerCase()
    if (!(normalized in ["success", "failed"])) {
        normalized = "unknown"
    }
    def detailText = (detail ?: "").toString()
    def nowText = new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone)
    def count = (device.currentValue("cloudBackupCount") ?: 0).toInteger() + 1

    sendEvent(name: "cloudBackupStatus", value: normalized,
        descriptionText: "Cloud backup ${normalized}: ${detailText}")
    sendEvent(name: "cloudBackupDetail", value: detailText)
    sendEvent(name: "lastCloudBackup", value: nowText)
    sendEvent(name: "cloudBackupCount", value: count)

    if (logEnable) {
        log.debug "${device.label}: cloudBackup recorded status=${normalized} detail=${detailText}"
    }
}

def clearCloudBackup() {
    sendEvent(name: "cloudBackupStatus", value: "unknown",
        descriptionText: "Cleared manually")
    sendEvent(name: "cloudBackupDetail", value: "")
    sendEvent(name: "lastCloudBackup", value: "")
    sendEvent(name: "cloudBackupCount", value: 0)
}
