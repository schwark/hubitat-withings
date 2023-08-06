/**
 *  Withings Support for Hubitat
 *  Schwark Satyavolu
 *
 */

 def version() {"0.1.1"}

import hubitat.helper.InterfaceUtils

def appVersion() { return "4.0" }
def appName() { return "Withings Support" }
def updateFreq() { return 60 }

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
	page(name: "configPage")
}

def configPage(){
    dynamicPage(name: "configPage", title: "Configure/Edit User Auth Codes:", install: true, uninstall: true) {
		section("Get your Authorization Code for each user by logging into your Withings account once per user <a target='_blank' rel='noreferrer noopener' href='https://account.withings.com/oauth2_user/authorize2?response_type=code&client_id=5801801f0848ed8c1d740253e9c78c43fc11da46e147cf5477e78e6d2f208302&scope=user.info,user.metrics,user.activity&redirect_uri=https://www.yahoo.com/&state=notreallyusedinthisapp'>here</a>"){
			input("debugMode", "bool", title: "Enable debugging", defaultValue: true)
			input("numUsers", "number", title: "How many users?", submitOnChange: true, range: "1..10")
			if(numUsers){
				for(i in 1..numUsers){
					input "authCode${i}", "text", title: "Authorization Code for User ${i}", required: true
				}
			}
		}
	}
}


def installed() {
	initialize()
}

def updated() {
	getToken()
	refresh()
	initialize()
}

def getSleepUpdate() {
	def since = (now()/1000).toInteger() - updateFreq()
	for(i in 1..numUsers) {
		withings(user: i, verb: 'sleep', action: 'getsummary', lastupdate: since, data_fields: 'night_events')
	}
}

def initialize() {
	unschedule()
	runEvery1Minute("getSleepUpdate")
}

def uninstalled() {
	unschedule()
	def children = getAllChildDevices()
	log.info("uninstalled: children = ${children}")
	children.each {
		deleteChildDevice(it.deviceNetworkId)
	}
}

def refresh() {
	getDevices()
	getSleepUpdate()
}

/*
[body:[more:false, offset:0, series:[[created:1691302331, data:[night_events:{"1":[0,4140],"2":[1860,2280,3780,1260,3840],"3":[3120,3480,1680,3720,2220],"4":[3960,10260]}], date:2023-08-06, enddate:1691312520, hash_deviceid:d6e462e9979d57079d75ba82deeed035b27b9c7b, id:3609165417, model:32, model_id:63, modified:1691313009, startdate:1691298300, timezone:America/Los_Angeles]]], status:0]
*/

def parseResponse(map, resp) {
	def user = map.user
	def cmd = map.action
	def json = resp.data
	debug(json)
	if(json.status == 0) {
		if('requesttoken' == cmd) {
			state["accessToken${user}"] = json.body.access_token
			state["refreshToken${user}"] = json.body.refresh_token
			state["tokenExpiry${user}"] = json.body.expires_in
			runIn(json.body.expires_in - 100, 'renewToken')
		} else if ('getdevice' == cmd) {
			state["devices${user}"] = json.body.devices
		} else if ('getsummary' == cmd) {
			def points = json.body.series
			points?.each() { 
				if (it.data?.night_events) {
					if(it.data.night_events['1']) {
						// got in bed

					} else if(it.data.night_events['4']) {
						// got out of bed

					}
				} 
			}
		}
	} else if (json.status == 401 || json.status <= 200) {
		logError('Authentication failed - please use new authorization code to update preferences')
		unschedule()
	}
}

def renewToken() {
	getToken(true)
}

def getToken(renew=false) {
	for(i in 1..numUsers) {
		def grant_type = (renew ? 'refresh_token' : 'authorization_code')
		withings(user: i, verb: 'oauth2', action: 'requesttoken', grant_type: grant_type, code: settings."authCode${i}", client_id: '5801801f0848ed8c1d740253e9c78c43fc11da46e147cf5477e78e6d2f208302',
			client_secret: 'a376fb34b3d397bf9916c5d2f73534a985382e605857046f49618873ffc95214',
			redirect_uri: 'https://www.yahoo.com/')
	}
}

def getDevices() {
	for(i in 1..numUsers) {
		withings(user: i, verb: 'user', action: 'getdevice')
	}
}

def withings(map) {
	def verb = map.verb
	def user = map.user
	def params = map.clone()
	def accessToken = state["accessToken${user}"]
	params.remove('verb')
	params.remove('user')

	def headers = accessToken ? [
		Authorization: "Bearer ${accessToken}"
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

private makeChildDeviceId(user, modelId) {
	return "WITHINGS-${user}-${modelId}"
}

private createChildDevice(user, device) {
	def deviceId = makeChildDeviceId(user, device.model_id)
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

private logError(e, fromMethod="") {
    log.error("[Withings] ERROR: (${fromMethod}): ${e}")
}
