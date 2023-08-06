/**
 *  Withings Support for Hubitat
 *  Schwark Satyavolu
 *
 */

 def version() {"0.1.1"}

import hubitat.helper.InterfaceUtils

def appVersion() { return "4.0" }
def appName() { return "Withings Support" }

definition(
	name: "${appName()}",
	namespace: "schwark",
	author: "Schwark Satyavolu",
	description: "This adds support for Withings",
	category: "Convenience",
	iconUrl: "https://play-lh.googleusercontent.com/ALmudASgoLjE8y_qBnIfquvayHpPK02PAQZ_CjMUASx_VEkE_68gEchbIRFhP4LuOw",
	iconX2Url: "https://play-lh.googleusercontent.com/ALmudASgoLjE8y_qBnIfquvayHpPK02PAQZ_CjMUASx_VEkE_68gEchbIRFhP4LuOw",
	singleInstance: true,
	importUrl: "https://raw.githubusercontent.com/schwark/hubitat-withings/main/withings.groovy"
)

preferences {
	section("Get your Authorization Code by logging into your Withings account <a target='_blank' rel='noreferrer noopener' href='https://account.withings.com/oauth2_user/authorize2?response_type=code&client_id=5801801f0848ed8c1d740253e9c78c43fc11da46e147cf5477e78e6d2f208302&scope=user.info,user.metrics,user.activity&redirect_uri=https://www.yahoo.com/&state=notreallyusedinthisapp'>here</a>") {
		input "debugMode", "bool", title: "Enable debugging", defaultValue: true
		input "authCode", "text", title: "Authorization Code", required: true
	}
}

def installed() {
	initialize()
}

def updated() {
	refresh()
}

def initialize() {
	unschedule()
}

def uninstalled() {
	def children = getAllChildDevices()
	log.info("uninstalled: children = ${children}")
	children.each {
		deleteChildDevice(it.deviceNetworkId)
	}
}

def refresh() {
	getToken()
	getDevices()
}

def parseResponse(map, resp) {
	def json = resp.data
	def cmd = map.action
	debug(json)
	if(json.status == 0) {
		state.retry = false
		if('requesttoken' == cmd) {
			state.accessToken = json.body.access_token
			state.refreshToken = json.body.refresh_token
		} else if ('getdevice' == cmd) {
			state.devices = json.body.devices
		}
	} else if (json.status <= 200 && !state.retry) {
		state.retry = true
		// authentication failed, retry
		debug("authentication failed.. retrying...")
		getToken(true)
		pauseExecution(1000)
		withings(map)
	}
}

def getToken(force=false) {
	if(force || !state.accessToken) {
		def grant_type = (state.accessToken ? 'refresh_token' : 'authorization_code')
		withings(verb: 'oauth2', action: 'requesttoken', grant_type: grant_type, code: authCode, client_id: '5801801f0848ed8c1d740253e9c78c43fc11da46e147cf5477e78e6d2f208302',
			client_secret: 'a376fb34b3d397bf9916c5d2f73534a985382e605857046f49618873ffc95214',
			redirect_uri: 'https://www.yahoo.com/')
	}
}

def getDevices() {
	withings(verb: 'user', action: 'getdevice')
}

def withings(map) {
	def verb = map.verb
	def params = map
	params.remove('verb')

	def headers = state.accessToken ? [
		Authorization: "Bearer ${state.accessToken}"
    ] : [:]

	def url = "https://wbsapi.withings.net/v2/${verb}"
	debug("${url} -> ${headers} -> ${params}")

	httpPost([uri: url, headers: headers, body: params, requestContentType: 'application/x-www-form-urlencoded', contentType: 'application/json'], { parseResponse(map, it) } )
}

private getComponent(device) {
	def components = [sleep: 'Generic Component Switch']
	def type = device.type.toLowerCase()
	def component = null

	components.each { k,v ->
		if (type.contains(k)) component = v
	}
	return component
}

private createChildDevice(device) {
	def deviceId = device.deviceid
	def label = device.model
	def createdDevice = getChildDevice(deviceId)
	def component = createdDevice ? null : getComponent(device)

	if(component) {
		try {
			// create the child device
			addChildDevice("hubitat", component, deviceId, [label : "${label}", isComponent: false, name: "${label}"])
			createdDevice = getChildDevice(deviceId)
			def created = createdDevice ? "created" : "failed creation"
			log.info("Withings Switch: id: ${deviceId} label: ${label} ${created}")
		} catch (e) {
			logError("Failed to add child device with error: ${e}", "createChildDevice()")
		}
	} else {
		debug("Child device type: ${type} id: ${deviceId} already exists", "createChildDevice()")
		if(label && label != createdDevice.getLabel()) {
			createdDevice.sendEvent(name:'label', value: label, isStateChange: true)
		}
	}
	return createdDevice
}

void componentRefresh(cd) {
    debug("received refresh request from ${cd.displayName}")
    refresh()
}

def componentOn(cd) {
    debug("received on request from DN = ${cd.name}, DNI = ${cd.deviceNetworkId}")
	def id = cd.deviceNetworkId
}

def componentOff(cd) {
    debug("received off request from DN = ${cd.name}, DNI = ${cd.deviceNetworkId}")
	def id = cd.deviceNetworkId
}

private debug(logMessage, fromMethod="") {
    if (debugMode) {
        def fMethod = ""

        if (fromMethod) {
            fMethod = ".${fromMethod}"
        }

        log.debug("[Withings] DEBUG: ${fMethod}: ${logMessage}")
    }
}

private logError(fromMethod, e) {
    log.error("[Withings] ERROR: (${fromMethod}): ${e}")
}
