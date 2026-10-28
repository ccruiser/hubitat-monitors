/*
 * Plumcrest Hub Updater (virtual device driver) - SPIKE, v0.2.2
 *
 * Casey Cruise / Muse, Oct 23, 2026. v0.2.2 writes "off" (not
 * "") to applyArmedUntil on disarm - empty-string events do not
 * clear the device-page value (observed in the arm test below).
 * v0.2.1 fixes two Hubitat sandbox rejections
 * (System.currentTimeMillis, URLEncoder) that stock groovyc
 * cannot see. v0.2.0 adds the arm timer below;
 * v0.1.0 was the initial endpoint spike (live-proven the same day
 * on the unsecured Living Room hub: /hub/cloud/* answers driver
 * calls at http://127.0.0.1:8080, status IDLE).
 *
 * Purpose: give Hubitat platform updates the same shape as the
 * fleet's DietPi/Tailscale upgrader pattern - detect, propose,
 * human go, apply, verify - without pretending the hub offers an
 * update API. It does not. What it offers (reverse-engineered from
 * the hub's own Check for Updates page code, platform 2.5.2.139;
 * see hubitat-platform-update-endpoints-recon-2026-10-23.md) is a
 * set of internal endpoints the page itself calls:
 *
 *   GET /hub/cloud/checkForUpdate    -> {"status": ...}
 *   GET /hub/cloud/updatePlatform?allHubs=false&purgeLogs=<bool>&version=<v>
 *                                    -> {"success": true}
 *   GET /hub/cloud/checkUpdateStatus -> download/apply progress
 *   GET /hub/cloud/clearUpdateStatus -> cancel / clear state
 *
 * This driver wraps those endpoints in three commands. It is
 * HUMAN-INVOKED ONLY: nothing here is scheduled, and checkForUpdate
 * runs only when a person (or, on that person's explicit go, the
 * Maker API) calls it.
 *
 * Safety gates on applyUpdate, by design:
 *  - The enableApply preference defaults to FALSE. With it off,
 *    applyUpdate refuses and says so in the log and in lastResult.
 *  - The toggle is a timed ARM, not a standing state (the fleet
 *    upgrader-bot rule, ported): flipping enableApply on opens a
 *    window of applyArmMinutes (default 60) and the driver turns
 *    the toggle back OFF itself when the window closes, after a
 *    successful apply, or at boot if the window already expired.
 *    The window is a ceiling, never a trigger - its expiry can
 *    only disarm, never apply. The current deadline shows in the
 *    applyArmedUntil attribute.
 *  - applyUpdate applies only the exact version string returned by
 *    a prior checkForUpdate that found UPDATE_AVAILABLE. No check,
 *    no apply; no version argument is accepted from the caller.
 *  - allHubs is hard-coded false: one hub at a time, canary style.
 *    Hub Mesh update-all is deliberately not exposed.
 *
 * Honest limits (spike):
 *  - The endpoints are undocumented and build-sensitive; a future
 *    platform version can move them. Treat every apply as
 *    unproven until the hub's installed version actually changes
 *    afterwards - that check belongs to the off-hub collector /
 *    hub-check hook, never to this driver's apply response.
 *  - Unproven on a hub with Hub Login Security enabled (the Game
 *    Room pilot): whether a driver-issued localhost call is
 *    answered the same way as on an unsecured hub is a live test
 *    still to run.
 *  - The internal-call base URL is a second open question: drivers
 *    conventionally use http://127.0.0.1:8080, while the update
 *    page's calls were observed on the hub's normal web port.
 *    hubGet() tries 8080 first and falls back to the plain host,
 *    logging which base answered, so the first live check settles
 *    it with evidence instead of a guess.
 *
 * Written with dynamic typing throughout (no Hubitat class
 * references) so a stock groovyc can syntax/compile-check it in
 * CI - see .github/workflows/main.yml in this repo.
 */

metadata {
    definition(
        name: "Plumcrest Hub Updater",
        namespace: "plumcrest",
        author: "Casey Cruise"
    ) {
        capability "Sensor"
        capability "Actuator"

        attribute "updateStatus", "string"     // NO_UPDATE_AVAILABLE | UPDATE_AVAILABLE | DOWNLOAD_* | IDLE | ...
        attribute "latestVersion", "string"    // version offered by the last successful check
        attribute "updatePercent", "number"    // progress reported by checkUpdateStatus, when given
        attribute "lastResult", "string"       // outcome of the last command run here
        attribute "lastCheckAt", "string"      // local time of the last check
        attribute "applyArmedUntil", "string"  // arm deadline while enableApply is on; "off" when disarmed

        command "checkForUpdate"
        command "refreshUpdateStatus"
        command "applyUpdate"
        command "clearUpdateStatus"
    }

    preferences {
        input name: "enableApply", type: "bool",
            title: "Arm applyUpdate (starts a real platform update; auto-disarms when the arm window closes)",
            defaultValue: false
        input name: "applyArmMinutes", type: "number",
            title: "Apply arm window in minutes (how long the toggle above stays on before the driver turns it off)",
            defaultValue: 60
        input name: "purgeLogsOnUpdate", type: "bool",
            title: "Purge logs when applying an update (the update page's purgeLogs option)",
            defaultValue: false
        input name: "logEnable", type: "bool",
            title: "Enable debug logging", defaultValue: false
    }
}

def installed() {
    log.info "${device.label}: installed"
    initialize()
}

def updated() {
    log.info "${device.label}: preferences updated, apply armed is ${enableApply == true}, debug logging is ${logEnable == true}"
    if (logEnable) runIn(1800, "logsOff")
    syncArmState()
    initialize()
}

def initialize() {
    if (device.currentValue("updateStatus") == null) {
        sendEvent(name: "updateStatus", value: "unknown",
            descriptionText: "No update check has run yet")
    }
    if (device.currentValue("lastResult") == null) {
        sendEvent(name: "lastResult", value: "")
    }
    if (device.currentValue("applyArmedUntil") == null) {
        sendEvent(name: "applyArmedUntil", value: "off")
    }
    // After any hub reboot (including the one an update causes),
    // an armed toggle whose window survived gets its countdown
    // restarted for the remaining time; an expired one is disarmed
    // here rather than left on.
    if (enableApply == true) {
        syncArmState()
    }
}

// Reconciles the arm timer with the enableApply preference and
// the stored deadline. Called on preference saves and at boot.
// A live deadline is never extended by a re-save - only a fresh
// arm (toggle was off, or no deadline stored) starts a new window.
private syncArmState() {
    if (enableApply != true) {
        unschedule("applyArmExpired")
        state.armDeadline = null
        if (device.currentValue("applyArmedUntil")) {
            sendEvent(name: "applyArmedUntil", value: "off")
        }
        return
    }
    def nowSec = (long) (new Date().time / 1000)
    def deadline = state.armDeadline ? state.armDeadline.toLong() : 0L
    if (deadline <= nowSec) {
        def minutes = (applyArmMinutes ?: 60).toInteger()
        if (minutes < 1) minutes = 1
        deadline = nowSec + (minutes * 60)
        state.armDeadline = deadline
    }
    def remaining = (long) (deadline - nowSec)
    if (remaining < 1) remaining = 1
    runIn(remaining, "applyArmExpired", [overwrite: true])
    def untilText = new Date(deadline * 1000).format("yyyy-MM-dd HH:mm:ss", location.timeZone)
    sendEvent(name: "applyArmedUntil", value: untilText)
    log.info "${device.label}: applyUpdate armed until ${untilText}"
}

// Scheduled handler: the arm window closed. Disarm only - this
// method can never start an update (ceiling, never a trigger).
def applyArmExpired() {
    if (enableApply == true) {
        device.updateSetting("enableApply", [value: "false", type: "bool"])
    }
    state.armDeadline = null
    sendEvent(name: "applyArmedUntil", value: "off")
    log.warn "${device.label}: apply arm window closed - applyUpdate disarmed"
    recordResult("applyUpdate disarmed: arm window expired")
}

// Immediate disarm after a successful apply: the arm is single-use.
private disarmApplyNow() {
    unschedule("applyArmExpired")
    if (enableApply == true) {
        device.updateSetting("enableApply", [value: "false", type: "bool"])
    }
    state.armDeadline = null
    sendEvent(name: "applyArmedUntil", value: "off")
}

def logsOff() {
    log.warn "${device.label}: debug logging disabled automatically"
    device.updateSetting("logEnable", [value: "false", type: "bool"])
}

private nowText() {
    return new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone)
}

private recordResult(text) {
    sendEvent(name: "lastResult", value: text)
    log.info "${device.label}: ${text}"
}

// GET a hub-internal path. Tries the driver-conventional internal
// base (127.0.0.1:8080) first, then the hub's normal web address on
// 127.0.0.1, and remembers which base answered in state so later
// calls go straight there. Returns the parsed response map, or null
// after logging the failure. Every response is logged verbatim at
// debug level: this spike's job is evidence.
private hubGet(path) {
    def bases = []
    if (state.workingBase) {
        bases << state.workingBase
    }
    ["http://127.0.0.1:8080", "http://127.0.0.1"].each { b ->
        if (!(b in bases)) bases << b
    }
    for (base in bases) {
        def uri = "${base}${path}"
        try {
            def result = null
            httpGet([uri: uri, contentType: "application/json"]) { resp ->
                if (logEnable) {
                    log.debug "${device.label}: GET ${uri} -> ${resp.status} ${resp.data}"
                }
                result = resp.data
            }
            if (result != null) {
                if (state.workingBase != base) {
                    state.workingBase = base
                    log.info "${device.label}: hub-internal base answering is ${base}"
                }
                if (result instanceof Map) return result
                // Some endpoints answer a JSON string body; parse it.
                try {
                    def parsed = new groovy.json.JsonSlurper().parseText(result.toString())
                    if (parsed instanceof Map) return parsed
                } catch (ignored) { }
                return [raw: result.toString()]
            }
        } catch (e) {
            if (logEnable) {
                log.debug "${device.label}: GET ${uri} failed: ${e.message}"
            }
        }
    }
    return null
}

private storeStatus(data) {
    if (data == null) return
    if (data.status != null) {
        sendEvent(name: "updateStatus", value: data.status.toString())
    }
    def offered = data.latestVersion ?: data.version
    if (offered != null && offered.toString() != "") {
        sendEvent(name: "latestVersion", value: offered.toString())
    }
    if (data.percent != null) {
        sendEvent(name: "updatePercent", value: data.percent)
    }
}

// Runs the hub's own update check (the same call the Check for
// Updates page makes). Detection only - never downloads or applies.
def checkForUpdate() {
    def data = hubGet("/hub/cloud/checkForUpdate")
    sendEvent(name: "lastCheckAt", value: nowText())
    if (data == null) {
        recordResult("checkForUpdate: no answer from either hub-internal base (see debug log)")
        return
    }
    storeStatus(data)
    recordResult("checkForUpdate: status=${data.status} latestVersion=${device.currentValue('latestVersion')}")
}

// Polls download/apply progress (DOWNLOAD_STARTED, ..._IN_PROGRESS,
// DOWNLOAD_FAILED, IDLE, ...). The completion signal that matters is
// the hub rebooting onto a new installed version, verified off-hub.
def refreshUpdateStatus() {
    def data = hubGet("/hub/cloud/checkUpdateStatus")
    if (data == null) {
        recordResult("refreshUpdateStatus: no answer from either hub-internal base (see debug log)")
        return
    }
    storeStatus(data)
    recordResult("refreshUpdateStatus: status=${data.status} percent=${data.percent}")
}

// Cancels / clears the hub's update state (the page's cancel path).
def clearUpdateStatus() {
    def data = hubGet("/hub/cloud/clearUpdateStatus")
    if (data == null) {
        recordResult("clearUpdateStatus: no answer from either hub-internal base (see debug log)")
        return
    }
    recordResult("clearUpdateStatus: success=${data.success}")
}

// Starts a platform update for the version the last check offered.
// Refuses unless BOTH gates pass: the enableApply preference is on,
// and a prior check on this device returned UPDATE_AVAILABLE with a
// version string. There is deliberately no way to pass a version in
// from outside - what gets applied is exactly what the hub offered.
def applyUpdate() {
    if (enableApply != true) {
        recordResult("applyUpdate REFUSED: the Enable applyUpdate preference is off")
        log.warn "${device.label}: applyUpdate called while enableApply is off - refused"
        return
    }
    def status = device.currentValue("updateStatus")
    def version = device.currentValue("latestVersion")
    if (status != "UPDATE_AVAILABLE" || version == null || version.toString() == "") {
        recordResult("applyUpdate REFUSED: no pending update on record (status=${status}, latestVersion=${version}) - run checkForUpdate first")
        log.warn "${device.label}: applyUpdate with nothing pending - refused"
        return
    }
    // Hub version strings are hub-generated (digits and dots, e.g.
    // 2.5.2.139) and need no encoding; the one character that could
    // break the query is a space. (java.net.URLEncoder is a static
    // call Hubitat's sandbox rejects - same class of error as
    // System.currentTimeMillis, which v0.2.0 shipped and v0.2.1 fixes.)
    def encoded = version.toString().replace(" ", "%20")
    def path = "/hub/cloud/updatePlatform?allHubs=false&purgeLogs=${purgeLogsOnUpdate == true}&version=${encoded}"
    def data = hubGet(path)
    if (data == null) {
        recordResult("applyUpdate: no answer from either hub-internal base (see debug log) - update NOT started; verify on the hub before retrying")
        return
    }
    if (data.success == true) {
        sendEvent(name: "updateStatus", value: "UPDATE_STARTED",
            descriptionText: "Apply accepted for version ${version}; hub will download and reboot")
        disarmApplyNow()
        recordResult("applyUpdate: hub accepted update to ${version} and the arm is spent (toggle off) - proof is the installed version changing after the reboot, verified off-hub")
    } else {
        recordResult("applyUpdate: hub answered success=${data.success} - treat as NOT started; check the hub UI")
    }
}
