/**
 *  Hunter Douglas PowerView Shade
 *
 *  Copyright 2017 Chris Lang
 *
 *  Ported to Hubitat by Brian Ujvary
 *  Additional modifications by Eric Will (corinuss)
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 *  Change Log:
 *    01/18/2021 v1.1 - Refresh requests now explicitly request a physical refresh when polling the shade (corinuss)
 *                    - Improved Battery status for Recharable Battery Wands (corinuss)
 *    05/10/2020 v1.0 - Initial release
 *
 */
metadata {
    definition(name: "Hunter Douglas PowerView Shade", namespace: "hdpowerview", author: "Chris Lang", importUrl: "https://raw.githubusercontent.com/bujvary/hubitat/master/drivers/hunter-douglas-powerview-shade.groovy") {
        capability "Actuator"
        capability "Battery"
        capability "Refresh"
        capability "Sensor"
        capability "Switch Level"
        capability "Window Shade"

        command "calibrate"
        command "jog"
        command "setBottomPosition", ["number"]
        command "setTopPosition", ["number"]

        attribute "bottomPosition", "number"
        attribute "topPosition", "number"
    }

    preferences {
        input("logEnable", "bool", title: "Enable logging", required: false, defaultValue: true)
        input(
            "openTopByDefault",
            "bool",
            title: "Open top by default?",
            description: "If set, Open and Close commands will operate the top position by default.  Otherwise, they will operate the bottom posistion by default.",
            required: false,
            defaultValue: false)            
    }
}

def installed() {
    initialize()
}

def initialize() {
    if (logEnable) runIn(900, logsOff)
}

// Always perform a full physical refresh, since the user is requesting it.
def refresh() {
    parent?.pollShade(device, refresh: true)
}

public handleEvent(shadeJson) {
    if (logEnable) log.debug "handleEvent: shadeJson = ${shadeJson}"
    if (shadeJson?.positions) {
        def positions = shadeJson.positions
        if (positions.posKind1) {
            updatePosition(positions.position1, positions.posKind1)
        }
        if (positions.posKind2) {
            updatePosition(positions.position2, positions.posKind2)
        }
    } else {
        // If a result doesn't include position, sometimes reissuing the poll will return it.
        def now = now()
        if (!state?.lastPollRetry || (state?.lastPollRetry - now) > (60 * 10 * 1000)) {
            if (logEnable) log.debug "event didn't contain position, retrying poll"
            state?.lastPollRetry = now
            refresh()
        }
    }

    if (shadeJson?.batteryStrength) {
        def batteryStrengthMax = 255

        // 0 = Unknown, 1 = Hardwired, 2 = Battery Wand, 3 = Rechargable Battery Wand
        if (shadeJson?.batteryKind == 3) {
            // For recharagable batteries, the max is closer to 170.
            // (Prevents fresh rechargable batteries from appearing as ~60% charge.)
            batteryStrengthMax = 170
        }

        def batteryPercent = Math.min((int)(shadeJson.batteryStrength * 100 / batteryStrengthMax), 100)
        sendEvent(name: "battery", value: batteryPercent, , unit: "%")
    }
	
	state.batteryStatus = shadeJson.batteryStatus;  // 0 = No Status Available, 1 = Low, 2 = Medium, 3 = High, 4 = Plugged In
	state.shadeType = shadeJson.type;  // Need the shade types from Hunter Douglas so the right functions are used against the right shade type
	state.shadeCapabilities = shadeJson.capabilities;
}

def updatePosition(position, posKind) {
    def intPosition = (int)(position * 100 / 65535)
    def eventName = (posKind == 1) ? "bottomPosition" : "topPosition"
    if (logEnable) log.debug "sending event ${eventName} with value ${intPosition}"

    sendEvent(name: eventName, value: intPosition)
    sendEvent(name: "level", value: intPosition)

    if (intPosition >= 99) {
        stateName = 'open'
    } else if (intPosition > 0) {
        stateName = 'partially open'
    } else {
        stateName = 'closed'
    }
    sendEvent(name: 'windowShade', value: stateName)
}

// parse events into attributes
def parse(String description) {}

// handle commands
def open() {
    if (logEnable) log.debug "Executing 'open'"
    
    //TODO Figure out what these capability bits mean.  So far only have seen values 0, 6 and 7.
    // 0     = Standard bottom-up?
    // Bit 0 = Bottom-Up rail exists
    // Bit 1 = Top-Down rail exist?
    // Bit 2 = Not standard...?
    switch (state.shadeCapabilities) {
        case 0:     // Standard
            parent.setPosition(device, [position: 100])
            break
        case 6:     // Top-Down
            parent.setPosition(device, [position: 0])
            break;
        case 7:     // Top-Down/Bottom-Up
        default:
            if (openTopByDefault)
                parent.setPosition(device, [bottomPosition: 0, topPosition: 100])
            else
                parent.setPosition(device, [bottomPosition: 100, topPosition: 0])
            break
    }
}

def close() {
    if (logEnable) log.debug "Executing 'close'"
    
    switch (state.shadeCapabilities) {
        case 0:     // Standard
            parent.setPosition(device, [position: 0])
            break
        case 6:     // Top-Down
            parent.setPosition(device, [position: 100])
            break
        case 7:     // Top-Down/Bottom-Up
        default:
            parent.setPosition(device, [bottomPosition: 0, topPosition: 0])
            break
    }
}

def presetPosition() {}

def calibrate() {
    parent.calibrateShade(device)
}

def jog() {
    parent.jogShade(device)
}

def setBottomPosition(bottomPosition) {
    bottomPosition = Math.min(Math.max(bottomPosition.intValue(), 0), 100)
    parent.setPosition(device, [bottomPosition: bottomPosition])
}

def setTopPosition(topPosition) {
    topPosition = Math.min(Math.max(topPosition.intValue(), 0), 100)
    parent.setPosition(device, [topPosition: topPosition])
}

def setPosition(position) {
    position = Math.min(Math.max(position.intValue(), 0), 100)
    parent.setPosition(device, [position: position])
}

def logsOff() {
    log.warn "Debug logging disabled."
    device.updateSetting("logEnable", [value: "false", type: "bool"])
}