/*
 *  Plumcrest Hub Backup
 *  ====================
 *  Production driver: pushes this hub's newest local backup
 *  off the hub to the Util2 hub-backup receiver, which stores
 *  it on plumnas (home_lab_backups/hubitat). Backups are
 *  encrypted H2 databases carrying hub credential material -
 *  treat the stored copies as sensitive.
 *
 *  Version 1.0.0 (the version lives HERE, in the code - the
 *  driver name never carries one, so updates ride the repo
 *  refresh with no device migration; Casey rule, Oct 29, 2026).
 *
 *  How it works (proven end to end Oct 29, 2026 by the
 *  Plumcrest Hub Backup Probe v0.15.0, Gate A + Gate B):
 *   1. Diagnostic Tool API on loopback :8081: POST /newLogin
 *      with this hub's MAC address (uppercased, separators
 *      stripped) as the bare form body -> UTSESSION cookie.
 *      Only the MAC is needed on this path - no platform
 *      credentials are stored or used. The MAC is itself a
 *      recovery-grade credential; it lives only in this
 *      device's preferences on the hub and is never logged.
 *   2. GET /api/backups (newest first), then
 *      GET /api/downloadBackup/<newest> - the store's RAW
 *      tilde filename must be carried verbatim or the server
 *      404s. The backup's bytes are held only in a run-local
 *      variable: fetch -> POST -> drop. They never enter an
 *      attribute or state.
 *   3. POST the bytes to the receiver (X-Backup-Token +
 *      X-Backup-Name headers). The run is PUSH_OK only when
 *      the receiver reports storing exactly the byte count
 *      held; its SHA-256 lands in the lastSha256 attribute.
 *
 *  Commands:
 *   - backupNow: fetch the newest backup (normally last
 *     night's scheduled 3:01 AM backup) and push it. This is
 *     the nightly motion, however it is triggered.
 *   - createAndPushBackup: create a FRESH database backup
 *     via the main platform's :8080 create-and-download URL
 *     (60-second timeout - observed creates run ~25-28 s
 *     against a ~11 s platform default) and push it. If the
 *     create call does not hand the bytes back but a new
 *     backup landed in the store, fall back to fetching that
 *     newest backup via the diagnostic API and pushing it.
 *
 *  Triggering: a dedicated Maker API instance per hub (NOT
 *  the collector instances) exposes backupNow and the status
 *  attributes; Rule Machine can call backupNow as a custom
 *  action; or the optional built-in schedule below runs it
 *  nightly (default OFF - enable ONE trigger route, not
 *  several). Scheduled runs retry once, 15 minutes later,
 *  on failure; manual runs do not.
 *
 *  Status attributes (Maker/dashboard readable): lastResult,
 *  lastRunAt, lastFile, lastBytes, lastSha256, backupCount,
 *  newestBackup, health ("ok" after a PUSH_OK, "failed"
 *  after any other result), lastSummary, driverVersion.
 *
 *  Written with dynamic typing throughout (no Hubitat class
 *  references) so a stock groovyc can syntax/compile-check it.
 */

metadata {
    definition(
        name: "Plumcrest Hub Backup",
        namespace: "plumcrest",
        author: "Casey Cruise"
    ) {
        capability "Sensor"
        capability "Actuator"

        attribute "lastResult", "string"      // PUSH_OK | PUSH_MISMATCH | NOT_CONFIGURED | LOGIN_FAILED | HTTP_ERROR | CREATE_FAILED | EXCEPTION | NOT_RUN
        attribute "lastRunAt", "string"       // local time of the last run
        attribute "lastFile", "string"        // store filename most recently pushed (or attempted)
        attribute "lastBytes", "number"       // bytes held in the last run
        attribute "lastSha256", "string"      // receiver-reported SHA-256 of the stored file (PUSH_OK only)
        attribute "backupCount", "number"     // backups currently in the hub's store
        attribute "newestBackup", "string"    // newest filename in the hub's store
        attribute "health", "string"          // ok | failed | unknown
        attribute "lastSummary", "string"     // one-line human summary of the last run
        attribute "driverVersion", "string"   // version of this driver code

        command "backupNow"
        command "createAndPushBackup"
    }

    preferences {
        input name: "hubMac", type: "password",
            title: "Diagnostic Tool password (this hub's MAC address, copied unmodified from Settings > Hub Details; stored only in this device's preferences on the hub)",
            required: false
        input name: "receiverBase", type: "text",
            title: "Receiver base URL (Util2 hub-backup receiver, e.g. http://192.168.25.118:8642)",
            required: false
        input name: "pushToken", type: "password",
            title: "Receiver token (printed once by the receiver install on Util2; stored only in this device's preferences)",
            required: false
        input name: "scheduleEnabled", type: "bool",
            title: "Run nightly on the built-in schedule (leave off if Rule Machine or Maker triggers the backup instead)",
            defaultValue: false
        input name: "scheduleTime", type: "text",
            title: "Nightly run time, 24-hour HH:MM hub local (e.g. 03:30 - after the hub's own 03:01 backup)",
            defaultValue: "03:30", required: false
        input name: "logEnable", type: "bool",
            title: "Enable debug logging", defaultValue: false
    }
}

private version() { return "1.0.0" }

def installed() {
    log.info "${device.label}: installed (driver v${version()})"
    initialize()
    applySchedule()
}

def updated() {
    log.info "${device.label}: preferences updated, debug logging is ${logEnable == true}"
    if (logEnable) runIn(1800, "logsOff")
    initialize()
    applySchedule()
}

def initialize() {
    if (device.currentValue("driverVersion") == null
            || device.currentValue("driverVersion") != version()) {
        sendEvent(name: "driverVersion", value: version())
    }
    if (device.currentValue("lastResult") == null) {
        sendEvent(name: "lastResult", value: "NOT_RUN",
            descriptionText: "No backup run yet")
    }
    if (device.currentValue("health") == null) {
        sendEvent(name: "health", value: "unknown")
    }
    if (device.currentValue("lastBytes") == null) {
        sendEvent(name: "lastBytes", value: 0)
    }
    if (device.currentValue("backupCount") == null) {
        sendEvent(name: "backupCount", value: 0)
    }
}

def logsOff() {
    log.warn "${device.label}: debug logging disabled automatically"
    device.updateSetting("logEnable", [value: "false", type: "bool"])
}

// (Re)arm the optional nightly schedule from preferences.
// The cron is seconds-first Quartz form, hub local time.
private applySchedule() {
    unschedule("scheduledBackupRun")
    if (scheduleEnabled != true) return
    def t = scheduleTime == null ? "" : scheduleTime.toString().trim()
    if (t == "") t = "03:30"
    def parts = t.split(":")
    def hh = -1
    def mm = -1
    try {
        if (parts.length == 2) {
            hh = parts[0].toInteger()
            mm = parts[1].toInteger()
        }
    } catch (ignored) { }
    if (hh < 0 || hh > 23 || mm < 0 || mm > 59) {
        log.warn "${device.label}: schedule time '${t}' is not HH:MM - nightly schedule NOT armed"
        return
    }
    def cron = "0 ${mm} ${hh} * * ?"
    schedule(cron, "scheduledBackupRun")
    log.info "${device.label}: nightly backup scheduled for ${t} (cron ${cron})"
}

private nowText() {
    return new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone)
}

private todayText() {
    return new Date().format("yyyy-MM-dd", location.timeZone)
}

// Record one finished run: result + health + the one-line
// human summary (attribute and log line). Only small scalars
// are ever sent as attributes - never bodies or bytes.
private finishRun(result, whenText, summary) {
    sendEvent(name: "lastResult", value: result)
    sendEvent(name: "lastRunAt", value: whenText)
    sendEvent(name: "health", value: result == "PUSH_OK" ? "ok" : "failed")
    sendEvent(name: "lastSummary", value: summary)
    log.info "${device.label}: ${summary}"
    return result == "PUSH_OK"
}

// ---------------------------------------------------------------
// Response helpers (carried from the probe, where each was
// proven against the live hub on platform 2.5.2.139).
// ---------------------------------------------------------------

// Best-effort single header read. The response object's header
// API varies by platform build, so each shape is tried inside
// its own guard and "" comes back when none of them answer.
private extractHeader(resp, headerName) {
    try {
        def v = resp.getHeader(headerName)
        if (v != null && v.toString() != "") return v.toString()
    } catch (ignored) { }
    try {
        def headers = resp.headers
        if (headers instanceof Map) {
            for (entry in headers) {
                if (entry.key != null && entry.key.toString().equalsIgnoreCase(headerName)) {
                    return entry.value == null ? "" : entry.value.toString()
                }
            }
        }
    } catch (ignored) { }
    return ""
}

private contentTypeOf(resp) {
    def fromHeader = extractHeader(resp, "Content-Type")
    if (fromHeader != "") return fromHeader
    try {
        if (resp.contentType != null) return resp.contentType.toString()
    } catch (ignored) { }
    return ""
}

// Keep only a file name: strip any path prefix, drop characters
// outside a conservative file-name set, and bound the length.
// Display/diagnostic use only - fetch URLs and push names use
// the store's RAW names (see parseDiagNames).
private cleanFileName(raw) {
    def name = raw == null ? "" : raw.toString()
    def cut = name.lastIndexOf("/")
    if (cut >= 0) name = name.substring(cut + 1)
    name = name.replaceAll("[^A-Za-z0-9._-]", "_")
    if (name.length() > 100) name = name.substring(0, 100)
    return name
}

private dispositionFileName(disposition) {
    if (disposition == null || disposition.toString() == "") return ""
    def m = (disposition.toString() =~ /filename="?([^";]+)"?/)
    if (m.find()) return cleanFileName(m.group(1))
    return ""
}

// Parse backup names out of the diagnostic API's JSON answer:
// {"backups":[{"name":"...lzf", ...}, ...]}, newest first.
// Two fallbacks keep the parse honest if the shape shifts:
// any quoted .lzf token, then any bare .lzf token (the
// platform may hand back an already-parsed object whose
// string form carries no quotes at all).
private parseDiagNames(bodyText, rawNames = false) {
    // rawNames: return the store's names VERBATIM. The real
    // filenames carry TILDE separators
    // (2026-10-29~2.5.2.139~manual.lzf); cleanFileName rewrites
    // tildes to underscores, which is right for display and
    // wrong for fetching - a cleaned name does not exist on
    // the store and the download 404s.
    def names = []
    if (bodyText == null) return names
    def byField = (bodyText =~ /"name"\s*:\s*"([^"]+)"/)
    while (byField.find()) {
        def candidate = rawNames ? byField.group(1) : cleanFileName(byField.group(1))
        if (candidate != "" && candidate.endsWith(".lzf") && !(candidate in names)) names << candidate
    }
    if (names.size() == 0) {
        def quoted = (bodyText =~ /"([^"]+\.lzf)"/)
        while (quoted.find()) {
            def candidate = rawNames ? quoted.group(1) : cleanFileName(quoted.group(1))
            if (candidate != "" && !(candidate in names)) names << candidate
        }
    }
    if (names.size() == 0) {
        def bare = (bodyText =~ /([A-Za-z0-9._~-]+\.lzf)/)
        while (bare.find()) {
            def candidate = rawNames ? bare.group(1) : cleanFileName(bare.group(1))
            if (candidate != "" && !(candidate in names)) names << candidate
        }
    }
    return names
}

// Harvest EVERY cookie pair a response sets into the jar map
// (name -> value), taking the first pair of each Set-Cookie
// header - the rest of the header is attributes. Values live
// only in the caller's map for the run's duration; debug
// output ever sees names only.
private harvestCookieMap(resp, jar) {
    def candidates = []
    try {
        def multi = resp.getHeaders("Set-Cookie")
        if (multi != null) {
            if (multi instanceof Collection) {
                multi.each { v -> if (v != null) candidates << v.toString() }
            } else {
                candidates << multi.toString()
            }
        }
    } catch (ignored) { }
    try {
        def single = resp.getHeader("Set-Cookie")
        if (single != null) candidates << single.toString()
    } catch (ignored) { }
    try {
        def headers = resp.headers
        if (headers instanceof Map) {
            for (entry in headers) {
                if (entry.key != null && entry.key.toString().equalsIgnoreCase("Set-Cookie")
                        && entry.value != null) {
                    if (entry.value instanceof Collection) {
                        entry.value.each { v -> if (v != null) candidates << v.toString() }
                    } else {
                        candidates << entry.value.toString()
                    }
                }
            }
        }
    } catch (ignored) { }
    for (header in candidates) {
        def matcher = (header =~ /^([^=;,]+)=([^;,]*)/)
        if (matcher.find()) {
            def cname = matcher.group(1).trim()
            // One stack path hands back the header WITH its
            // name attached ("Set-Cookie: UTSESSION=..."),
            // which would jar a bogus pair literally named
            // "Set-Cookie: UTSESSION" and put a malformed
            // Cookie header on the wire. Keep only clean
            // cookie names.
            if (cname ==~ /[A-Za-z0-9_-]+/) {
                jar[cname] = matcher.group(2).trim()
            }
        }
    }
}

private cookieHeaderValue(jar) {
    def parts = []
    for (entry in jar) {
        parts << (entry.key.toString() + "=" + entry.value.toString())
    }
    return parts.join("; ")
}

// Fold one /newLogin response into the capture map and the
// cookie jar. The JSON answer is {success, message} - the
// message is the session token. The token is kept only in
// the caller's run-local map; it is never logged or stored.
private harvestDiagLogin(resp, captured, jar) {
    try {
        if (resp.data != null) {
            if (resp.data instanceof Map) {
                def s = resp.data.success
                captured.success = (s == true || (s != null && s.toString() == "true"))
                if (resp.data.message != null) captured.token = resp.data.message.toString()
                captured.body = resp.data.toString()
            } else {
                captured.body = resp.data.toString()
            }
        }
    } catch (ignored) { }
    if (captured.body != "" && !captured.success
            && (captured.body.contains("\"success\":true") || captured.body.contains("\"success\": true"))) {
        captured.success = true
    }
    if (captured.token == "" && captured.body != "") {
        def m = (captured.body =~ /"message"\s*:\s*"([^"]*)"/)
        if (m.find()) captured.token = m.group(1)
    }
    if (jar != null) harvestCookieMap(resp, jar)
}

// ---------------------------------------------------------------
// Diagnostic Tool API calls (:8081, loopback - the proven
// path; the hub's main web UI refuses the hub itself, and
// the diagnostic service is the hub's own recovery door).
// ---------------------------------------------------------------

// POST /newLogin exactly as the diag UI does it (protocol
// read out of the tool's own JS bundle, Oct 29, 2026): the
// body is the bare MAC - already uppercased and separator-
// free - under a form-urlencoded content type. Returns
// [answered, status, success, token, body].
private diagLoginCall(base, macBare, jar) {
    def captured = [answered: false, status: 0, success: false, token: "", body: ""]
    def params = [
        uri: "${base}/newLogin",
        requestContentType: "application/x-www-form-urlencoded",
        contentType: "application/json",
        followRedirects: false,
        body: macBare
    ]
    try {
        httpPost(params) { resp ->
            captured.answered = true
            try { captured.status = resp.status == null ? 0 : resp.status.toInteger() } catch (ignored) { }
            harvestDiagLogin(resp, captured, jar)
        }
    } catch (e) {
        try {
            if (e.response != null) {
                captured.answered = true
                try { captured.status = e.response.status == null ? 0 : e.response.status.toInteger() } catch (ignored) { }
                harvestDiagLogin(e.response, captured, jar)
            }
        } catch (ignored) { }
        if (!captured.answered && logEnable) {
            log.debug "${device.label}: POST ${base}/newLogin raised (message only): ${e.message}"
        }
    }
    return captured
}

// One GET against the diagnostic service with the cookie jar
// and, when given, the session token as a Basic header.
// Hubitat raises error statuses as exceptions; the status is
// read back off the exception's response so callers can
// classify a 401 instead of mistaking it for "no answer".
// Returns [answered, status, contentType, location, body].
private diagCallAuth(method, uri, jar, authToken) {
    def captured = [answered: false, status: 0, contentType: "", location: "", body: ""]
    def params = [uri: uri]
    params.contentType = method == "GET_JSON" ? "application/json" : "text/html"
    def headers = [:]
    if (jar != null) {
        def jarHeader = cookieHeaderValue(jar)
        if (jarHeader != "") headers["Cookie"] = jarHeader
    }
    if (authToken != null && authToken != "") headers["Authorization"] = "Basic " + authToken
    if (!headers.isEmpty()) params.headers = headers
    try {
        httpGet(params) { resp ->
            captured.answered = true
            try { captured.status = resp.status == null ? 0 : resp.status.toInteger() } catch (ignored) { }
            captured.contentType = contentTypeOf(resp)
            captured.location = extractHeader(resp, "Location")
            if (resp.data != null) captured.body = resp.data.toString()
            if (jar != null) harvestCookieMap(resp, jar)
        }
    } catch (e) {
        try {
            if (e.response != null) {
                captured.answered = true
                try { captured.status = e.response.status == null ? 0 : e.response.status.toInteger() } catch (ignored) { }
                if (jar != null) harvestCookieMap(e.response, jar)
            }
        } catch (ignored) { }
        if (!captured.answered && logEnable) {
            log.debug "${device.label}: GET ${uri} raised (message only): ${e.message}"
        }
    }
    return captured
}

// GET a download path (diagnostic download, or the platform
// create-and-download URL). With keepBytes the body is read
// fully and handed back in `held` for the caller to push;
// otherwise it is measured and dropped. Returns [answered,
// status, contentType, fileName, bytes, isBytes, stub, held].
private diagDownloadCall(base, path, jar, authToken, timeoutSeconds = null, keepBytes = false) {
    def captured = [answered: false, status: 0, contentType: "", fileName: "", bytes: 0, isBytes: false, stub: "", held: null]
    def params = [uri: "${base}${path}", contentType: "application/octet-stream"]
    if (timeoutSeconds != null && timeoutSeconds > 0) params.timeout = timeoutSeconds
    def headers = [:]
    if (jar != null) {
        def jarHeader = cookieHeaderValue(jar)
        if (jarHeader != "") headers["Cookie"] = jarHeader
    }
    if (authToken != null && authToken != "") headers["Authorization"] = "Basic " + authToken
    if (!headers.isEmpty()) params.headers = headers
    try {
        httpGet(params) { resp ->
            captured.answered = true
            try { captured.status = resp.status == null ? 0 : resp.status.toInteger() } catch (ignored) { }
            captured.contentType = contentTypeOf(resp)
            captured.fileName = dispositionFileName(extractHeader(resp, "Content-Disposition"))
            def d = resp.data
            if (d != null) {
                if (d instanceof byte[]) {
                    captured.isBytes = true
                    captured.bytes = d.length
                    if (keepBytes) captured.held = d
                } else if (d instanceof InputStream) {
                    // Octet-stream answers arrive as an
                    // InputStream, not a byte[] - measuring
                    // the stream object itself was the probe's
                    // famous 35/36-byte "stub": the object's
                    // toString() while the real bytes flowed
                    // past unread. Read the stream fully.
                    try {
                        def arr = d.bytes
                        captured.isBytes = true
                        captured.bytes = arr == null ? 0 : arr.length
                        if (keepBytes) captured.held = arr
                    } catch (e) {
                        captured.stub = "stream read failed (message only): ${e.message}"
                    }
                } else if (d instanceof CharSequence) {
                    captured.bytes = d.toString().length()
                    // A SHORT text body is a status message by
                    // construction (it cannot be backup
                    // content), so surface it for diagnosis -
                    // unless it is shaped like a bare token,
                    // which stays masked.
                    if (captured.bytes > 0 && captured.bytes < 300) {
                        def s = d.toString().replace("\r", " ").replace("\n", " ").trim()
                        if (s ==~ /[0-9a-fA-F-]{32,40}/) {
                            captured.stub = "<token-shaped body, masked>"
                        } else {
                            captured.stub = s.length() > 80 ? s.substring(0, 80) : s
                        }
                    }
                }
            }
            if (jar != null) harvestCookieMap(resp, jar)
        }
    } catch (e) {
        try {
            if (e.response != null) {
                captured.answered = true
                try { captured.status = e.response.status == null ? 0 : e.response.status.toInteger() } catch (ignored) { }
                if (jar != null) harvestCookieMap(e.response, jar)
            }
        } catch (ignored) { }
        if (!captured.answered && logEnable) {
            log.debug "${device.label}: GET ${base}${path} raised (message only): ${e.message}"
        }
    }
    return captured
}

// The fetch chain: /newLogin, the backup list for the newest
// name, then GET /api/downloadBackup/<newest> keeping the
// bytes - cookie first, one Basic-token retry on a 401.
// (The probe also explored two further fallback shapes; the
// cookie shape won on the first try in every successful run,
// so production keeps the discipline lean.) Returns the
// download answer with targetName/backupCount set, a
// list-shaped failure when the list yields no names, or
// null when nothing answered.
private fetchNewestBackup(macRaw) {
    def base = "http://127.0.0.1:8081"
    def jar = [:]
    def macBare = macRaw.toUpperCase().replace(":", "").replace("-", "").replace(".", "").replace(" ", "")

    def login = diagLoginCall(base, macBare, jar)
    if (logEnable) {
        log.debug "${device.label}: newLogin via ${base} status=${login.status} success=${login.success} tokenPresent=${login.token != ""} cookies=${jar.keySet()}"
    }
    if (!login.answered) {
        return null
    }

    // The list, to name the target: the API lists newest
    // first, and parseDiagNames preserves that order. RAW
    // names - the download URL must carry the store's tilde
    // name verbatim or the server 404s.
    def list = diagCallAuth("GET_JSON", "${base}/api/backups", jar, "")
    def names = list.answered ? parseDiagNames(list.body.toString(), true) : []
    if (logEnable) {
        log.debug "${device.label}: list via ${base} status=${list.status} names=${names.size()} target=${names.size() > 0 ? names[0] : ""}"
    }
    if (names.size() == 0) {
        return [answered: list.answered, status: list.status, contentType: list.contentType,
            fileName: "", bytes: 0, isBytes: false, held: null, stub: "",
            shape: "list", targetName: "", backupCount: 0]
    }

    def target = names[0]
    def path = "/api/downloadBackup/" + target
    def dl = diagDownloadCall(base, path, jar, "", null, true)
    dl.shape = "cookie"
    dl.targetName = target
    dl.backupCount = names.size()
    if (dl.answered && dl.status == 401 && login.token != "") {
        if (logEnable) {
            log.debug "${device.label}: cookie download via ${base} -> 401; retrying with the Basic token"
        }
        dl = diagDownloadCall(base, path, jar, login.token, null, true)
        dl.shape = "cookie+basic"
        dl.targetName = target
        dl.backupCount = names.size()
    }
    if (logEnable) {
        log.debug "${device.label}: download via ${base} url=${base}${path} shape=${dl.shape} status=${dl.status} type=${dl.contentType} file=${dl.fileName} bytes=${dl.bytes} isBytes=${dl.isBytes} stub=${dl.stub}"
    }
    return dl
}

// The newest backup's RAW store name per the diagnostic API,
// or "" when the list cannot be read. Loopback only.
private diagNewestName(macRaw) {
    try {
        def jar = [:]
        def macBare = macRaw.toUpperCase().replace(":", "").replace("-", "").replace(".", "").replace(" ", "")
        def login = diagLoginCall("http://127.0.0.1:8081", macBare, jar)
        if (!login.answered) return ""
        def list = diagCallAuth("GET_JSON", "http://127.0.0.1:8081/api/backups", jar, "")
        if (!list.answered) return ""
        def names = parseDiagNames(list.body.toString(), true)
        return names.size() > 0 ? names[0] : ""
    } catch (e) {
        return ""
    }
}

// ---------------------------------------------------------------
// The receiver push and the run logic.
// ---------------------------------------------------------------

// POST the held bytes to the receiver. Returns [answered,
// status, storedBytes, sha256]; the receiver's JSON answer
// is never logged beyond those fields, and the token is
// never logged at all.
private pushBytesCall(name, arr) {
    def captured = [answered: false, status: 0, storedBytes: 0, sha256: ""]
    def rbase = receiverBase == null ? "" : receiverBase.toString().trim()
    while (rbase.endsWith("/")) rbase = rbase.substring(0, rbase.length() - 1)
    def token = pushToken == null ? "" : pushToken.toString()
    def params = [
        uri: "${rbase}/upload",
        requestContentType: "application/octet-stream",
        contentType: "application/json",
        headers: ["X-Backup-Token": token, "X-Backup-Name": name],
        body: arr,
        timeout: 120
    ]
    try {
        httpPost(params) { resp ->
            captured.answered = true
            try { captured.status = resp.status == null ? 0 : resp.status.toInteger() } catch (ignored) { }
            def d = resp.data
            if (d instanceof Map) {
                try { captured.storedBytes = d.bytes == null ? 0 : d.bytes.toString().toInteger() } catch (ignored) { }
                if (d.sha256 != null) captured.sha256 = d.sha256.toString()
            } else if (d != null) {
                def text = d.toString()
                def mb = (text =~ /"bytes"\s*:\s*(\d+)/)
                if (mb.find()) {
                    try { captured.storedBytes = mb.group(1).toInteger() } catch (ignored) { }
                }
                def ms = (text =~ /"sha256"\s*:\s*"([0-9a-f]+)"/)
                if (ms.find()) captured.sha256 = ms.group(1)
            }
        }
    } catch (e) {
        try {
            if (e.response != null) {
                captured.answered = true
                try { captured.status = e.response.status == null ? 0 : e.response.status.toInteger() } catch (ignored) { }
            }
        } catch (ignored) { }
        if (!captured.answered && logEnable) {
            log.debug "${device.label}: push to ${rbase}/upload raised (message only): ${e.message}"
        }
    }
    return captured
}

// What is missing from the preferences, or "" when the
// driver is fully configured.
private configProblem() {
    def missing = []
    if (hubMac == null || hubMac.toString().trim() == "") missing << "the Diagnostic Tool password (hub MAC)"
    if (receiverBase == null || receiverBase.toString().trim() == "") missing << "the Receiver Base URL"
    if (pushToken == null || pushToken.toString() == "") missing << "the Receiver Token"
    return missing.size() == 0 ? "" : missing.join(", ")
}

// Push one held byte array under one store name and record
// the outcome. PUSH_OK only when the receiver reports
// storing exactly the bytes held.
private pushHeld(name, held, whenText, verb) {
    sendEvent(name: "lastFile", value: name)
    sendEvent(name: "lastBytes", value: held.length)
    def push = pushBytesCall(name, held)
    if (logEnable) {
        log.debug "${device.label}: push file=${name} heldBytes=${held.length} pushAnswered=${push.answered} pushStatus=${push.status} storedBytes=${push.storedBytes}"
    }
    if (push.answered && push.status == 200 && push.storedBytes == held.length) {
        sendEvent(name: "lastSha256", value: push.sha256)
        return finishRun("PUSH_OK", whenText,
            "${verb}: PUSH_OK - fetched ${name} (${held.length} bytes) and pushed it to the receiver, which stored ${push.storedBytes} bytes (sha256 ${push.sha256}) on plumnas")
    }
    if (push.answered && push.status == 200) {
        return finishRun("PUSH_MISMATCH", whenText,
            "${verb}: PUSH_MISMATCH - the receiver answered 200 but stored ${push.storedBytes} bytes against ${held.length} held")
    }
    return finishRun("HTTP_ERROR", whenText,
        "${verb}: HTTP_ERROR - the push did not complete (pushAnswered=${push.answered} pushStatus=${push.status}) against ${held.length} held bytes")
}

// Classify one fetchNewestBackup answer and finish the run:
// push the bytes when they were truly held, otherwise record
// why not. Returns true only on PUSH_OK.
private finishFromFetch(fetch, whenText, verb) {
    if (fetch == null) {
        return finishRun("EXCEPTION", whenText,
            "${verb}: EXCEPTION - the diagnostic service did not answer")
    }
    if (fetch.backupCount != null && fetch.backupCount > 0) {
        sendEvent(name: "backupCount", value: fetch.backupCount)
        sendEvent(name: "newestBackup", value: fetch.targetName)
    }
    if (fetch.shape == "list") {
        def result = (fetch.status == 401 || fetch.status == 403) ? "LOGIN_FAILED" : "HTTP_ERROR"
        return finishRun(result, whenText,
            "${verb}: ${result} - the backup list yielded no backups (status=${fetch.status}); nothing to push")
    }
    if (fetch.status == 200 && fetch.isBytes && fetch.bytes > 100000 && fetch.held != null) {
        return pushHeld(fetch.targetName, fetch.held, whenText, verb)
    }
    def result = (fetch.status == 401 || fetch.status == 403) ? "LOGIN_FAILED" : "HTTP_ERROR"
    if (fetch.targetName != null && fetch.targetName != "") {
        sendEvent(name: "lastFile", value: fetch.targetName)
    }
    sendEvent(name: "lastBytes", value: fetch.bytes == null ? 0 : fetch.bytes)
    return finishRun(result, whenText,
        "${verb}: ${result} - the fetch did not yield backup bytes (status=${fetch.status} bytes=${fetch.bytes}); nothing pushed")
}

// The shared nightly motion: fetch the newest backup and
// push it. Returns true only on PUSH_OK.
private doBackupRun(verb) {
    def whenText = nowText()
    def problem = configProblem()
    if (problem != "") {
        return finishRun("NOT_CONFIGURED", whenText,
            "${verb}: NOT CONFIGURED - set ${problem} in the device preferences and run again")
    }
    def mac = hubMac == null ? "" : hubMac.toString()
    def fetch = null
    try {
        fetch = fetchNewestBackup(mac)
    } catch (e) {
        fetch = null
    }
    return finishFromFetch(fetch, whenText, verb)
}

// Command: fetch the newest backup and push it - the
// nightly motion, and the Maker / Rule Machine entry point.
def backupNow() {
    doBackupRun("backupNow")
}

// The built-in nightly schedule. One retry, 15 minutes
// later, on failure - and only one: the retry stamps the
// same date, so a second failure ends the night quietly in
// the attributes instead of looping.
def scheduledBackupRun() {
    def ok = doBackupRun("backupNow (scheduled)")
    if (!ok && state.retryDate != todayText()) {
        state.retryDate = todayText()
        runIn(900, "scheduledBackupRetry")
        log.info "${device.label}: scheduled backup did not complete; one retry in 15 minutes"
    }
}

def scheduledBackupRetry() {
    doBackupRun("backupNow (scheduled retry)")
}

// Command: create a fresh database backup and push it.
// The create-and-download call gets 60 seconds (settled
// Oct 29, 2026: observed creates run ~25-28 s against a
// ~11 s platform default). If the call hands no bytes back
// but a new backup landed in the store, fall back to the
// fetch chain for that newest backup.
def createAndPushBackup() {
    def verb = "createAndPushBackup"
    def whenText = nowText()
    def problem = configProblem()
    if (problem != "") {
        finishRun("NOT_CONFIGURED", whenText,
            "${verb}: NOT CONFIGURED - set ${problem} in the device preferences and run again")
        return
    }
    def mac = hubMac == null ? "" : hubMac.toString()

    def preNewest = diagNewestName(mac)
    if (logEnable) {
        log.debug "${device.label}: create pre-newest=${preNewest}"
    }

    def dl = null
    try {
        dl = diagDownloadCall("http://127.0.0.1:8080", "/hub2/createLocalDatabaseBackup", null, "", 60, true)
    } catch (e) {
        dl = null
    }
    if (dl != null && logEnable) {
        log.debug "${device.label}: create-download answered=${dl.answered} status=${dl.status} type=${dl.contentType} file=${dl.fileName} bytes=${dl.bytes} isBytes=${dl.isBytes} stub=${dl.stub}"
    }

    if (dl != null && dl.answered && dl.status == 200 && dl.isBytes && dl.bytes > 100000 && dl.held != null) {
        // Name the push with the store's own raw name for the
        // backup just created; the Content-Disposition name
        // is the fallback if the store cannot be re-read.
        def postNewest = diagNewestName(mac)
        def pushName = postNewest != "" ? postNewest : dl.fileName
        if (pushName == "") {
            finishRun("CREATE_FAILED", whenText,
                "${verb}: CREATE_FAILED - the create returned ${dl.bytes} bytes but no usable filename; nothing pushed")
            return
        }
        sendEvent(name: "newestBackup", value: pushName)
        pushHeld(pushName, dl.held, whenText, verb)
        return
    }

    // No bytes came back. Did the create at least land?
    def postNewest = diagNewestName(mac)
    if (logEnable) {
        log.debug "${device.label}: create post-newest=${postNewest}"
    }
    if (postNewest != "" && preNewest != "" && postNewest != preNewest) {
        log.info "${device.label}: ${verb}: the create landed ${postNewest} but returned no bytes; falling back to the fetch chain"
        def fetch = null
        try {
            fetch = fetchNewestBackup(mac)
        } catch (e) {
            fetch = null
        }
        finishFromFetch(fetch, whenText, verb)
        return
    }

    finishRun("CREATE_FAILED", whenText,
        "${verb}: CREATE_FAILED - the create call returned no bytes (status=${dl == null ? 0 : dl.status}) and no new backup landed in the store")
}
