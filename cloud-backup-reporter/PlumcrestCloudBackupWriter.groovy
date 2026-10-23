/*
 * Plumcrest Cloud Backup Writer (app)
 *
 * Casey Cruise / Muse, Oct 22, 2026. Version 1.0.0.
 *
 * Subscribes to the hub's built-in "cloudBackup" location event
 * and writes each result into a Plumcrest Cloud Backup Reporter
 * device (companion driver in this package), so the result can be
 * read through the Maker API by an off-hub collector.
 *
 * Pattern credit: the subscribe-and-handle shape follows the
 * example thebearmay (Hub Information Driver author) posted in
 * the Hubitat community thread "Cloud backup failure causes
 * Z-Wave radio to go offline" (Dec 2023), where bravenel noted
 * cloudBackup was being added as a location event. Success
 * semantics follow that example: the event counts as success
 * only when evt.value is "true" AND the description text does
 * not mention failure.
 *
 * Why an app at all: on a hub with Hub Login Security enabled,
 * the cloud backup status in /hub2/hubData is behind the login
 * gate for LAN callers. Apps run on the hub, inside the gate -
 * no credentials, no LAN call, nothing new exposed.
 *
 * Written with dynamic typing throughout (no Hubitat class
 * references) so a stock groovyc can syntax/compile-check it in
 * CI - see .github/workflows/groovy-syntax.yml in this repo.
 */

definition(
    name: "Plumcrest Cloud Backup Writer",
    namespace: "plumcrest",
    author: "Casey Cruise",
    description: "Writes cloudBackup location events into a Plumcrest Cloud Backup Reporter device for Maker API consumers.",
    category: "Utility",
    iconUrl: "",
    iconX2Url: "",
    iconX3Url: "",
    installOnOpen: true,
    singleInstance: true
)

preferences {
    page(name: "mainPage")
}

def mainPage() {
    dynamicPage(name: "mainPage", title: "", install: true, uninstall: true) {
        if (app.getInstallationState() == "COMPLETE") {
            section("Reporter device") {
                input "reporterDevice", "device.PlumcrestCloudBackupReporter",
                    title: "Cloud Backup Reporter device:", required: true
            }
            section("Logging") {
                input "logEnable", "bool",
                    title: "Enable debug logging", defaultValue: false
            }
        } else {
            section("") {
                paragraph "Click Done to install, then open the app again to pick the Reporter device."
            }
        }
    }
}

def installed() {
    state.isInstalled = true
    initialize()
}

def updated() {
    if (!state.isInstalled) { state.isInstalled = true }
    if (logEnable) runIn(1800, "logsOff")
    initialize()
}

def uninstalled() {
    unsubscribe()
}

def initialize() {
    unsubscribe()
    subscribe(location, "cloudBackup", "cloudBackupHandler")
    if (logEnable) log.debug "${app.label}: subscribed to cloudBackup location events"
}

def logsOff() {
    log.warn "${app.label}: debug logging disabled automatically"
    app.updateSetting("logEnable", [value: "false", type: "bool"])
}

def cloudBackupHandler(evt) {
    def detail = (evt.descriptionText ?: "").toString()
    def success = (evt.value.toString() == "true") &&
        !detail.toLowerCase().contains("fail")
    def status = success ? "success" : "failed"

    if (logEnable) {
        log.debug "${app.label}: cloudBackup event value=${evt.value} detail=${detail} -> ${status}"
    }

    if (reporterDevice) {
        reporterDevice.setCloudBackup(status, detail)
    } else {
        log.warn "${app.label}: cloudBackup event received but no Reporter device is selected"
    }
}
