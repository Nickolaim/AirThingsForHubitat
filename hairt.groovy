/**
 *
 *   File: hairt.groovy
 *
 *  Copyright 2022 Nick M
 *
 *  To use:
 *     - Register AirThings app and get Client ID and Client Secret.  Follow https://developer.airthings.com/docs/api-getting-started/index.html
 *     - From the AirThings dashboard https://dashboard.airthings.com/devices get ID of the device
 *     - Use the values in preferences
 *
 *  Notes:
 *     AirThings API for consumers has only one scope - `read:device:current_values`.
 *     As a result there is only one method that can be called - `https://ext-api.airthings.com/v1/devices/{serialNumber}/latest-samples`
 *
 *  References:
 *     - AirThings documentation: https://developer.airthings.com/
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 */

import java.text.DecimalFormat

def userAgent() { 'HAirT/1.0.0' }  // Hubitat-AirThings -> HAirT

metadata {
    definition(name: 'HAirT', namespace: 'nickolaim', author: 'Nick M') {
        capability 'Battery'
        capability 'CarbonDioxideMeasurement'
        capability 'PressureMeasurement'
        capability 'Refresh'
        capability 'RelativeHumidityMeasurement'
        capability 'TemperatureMeasurement'

        attribute 'airQualityPM1', 'number'
        attribute 'airQualityPM25', 'number'
        attribute 'radonShortTermAvg', 'number'
        attribute 'tile', 'string'
        attribute 'voc', 'number'
    }

    preferences {
        input('clientId', 'text', title: 'Client ID')
        input('clientSecret', 'text', title: 'Client Secret')
        input('serialNumber', 'text', title: 'Device Serial Number')
    }
}

def installed() {
    initialize()
}

def updated() {
    initialize()
}

def initialize() {
    log.debug 'Initialization'
    runEvery5Minutes refresh  // AirThings API has limit of 120 calls per hour
    requestSensorData()
}

def refresh() {
    log.debug 'Refreshing data'
    requestSensorData()
}

def requestSensorData() {
    def data = requestSensorDataWithToken()
    if (!data) {
        // If fails first time, assume issue with the token
        requestAccessToken()
        requestSensorDataWithToken()
    }
}

def sendEventUpdateTile(data, airThingsName, eventName, onScreenName, unit, tile, formatter) {
    if (data.containsKey(airThingsName)) {
        def value = data[airThingsName]
        sendEventIfChanged(name: eventName, value: value)
        tile +=  "<tr><td>${onScreenName}</td><td style='background-color: rgba(0, 255, 0, 0.0);'>" +
                "${formatter.format(value)} ${unit}</td></tr>"
    }

    return tile
}

def requestSensorDataWithToken() {
    try {
        httpGet([
                'uri'    : "https://ext-api.airthings.com/v1/devices/${serialNumber}/latest-samples",
                'headers': [
                        'User-Agent'   : userAgent(),
                        'Authorization': "Bearer ${state?.accessToken}"
                ],
        ]) { response ->
            if (response.status < 300) {
                def formatterDec = new DecimalFormat("###.0")
                def formatterInt = new DecimalFormat("###")

                def data = response.data['data']
                def tile = '<table style="display:inline;font-size:70%">'

                tile = sendEventUpdateTile(data, 'co2', 'carbonDioxide', 'CO2', 'ppm', tile, formatterInt)
                tile = sendEventUpdateTile(data, 'voc', 'voc', 'VOC', 'ppb', tile, formatterInt)
                tile = sendEventUpdateTile(data, 'radonShortTermAvg', 'radonShortTermAvg', 'Radon', 'pCi/L', tile, formatterDec)
                tile = sendEventUpdateTile(data, 'humidity', 'humidity', 'Humidity', '%rh', tile, formatterInt)
                tile = sendEventUpdateTile(data, 'temp', 'temperature', 'Temp', 'C', tile, formatterDec)
                tile = sendEventUpdateTile(data, 'pm25', 'airQualityPM25', 'PM 2.5', 'µg/m³', tile, formatterInt)
                tile = sendEventUpdateTile(data, 'pm1', 'airQualityPM1', 'PM 1', 'µg/m³', tile, formatterInt)
                tile = sendEventUpdateTile(data, 'pressure', 'pressure', 'Pressure', 'hPa', tile, formatterInt)
                tile = sendEventUpdateTile(data, 'battery', 'battery', 'Battery', '%', tile, formatterInt)

                tile += '</table'
                sendEventIfChanged(name: 'tile', value: tile)

            } else {
                log.error "Failed to receive latest sample.  Response status code: ${response.status}, data: ${response.data}"
                return false
            }
        }

        return true
    } catch (Exception e) {
        log.error "Exception while getting sensor data: ${e}"
    }
    return false
}


def requestAccessToken() {
    def params = [
            uri    : 'https://accounts-api.airthings.com/v1/token',
            headers: [
                    'Content-Type': 'application/x-www-form-urlencoded',
                    'User-Agent'  : userAgent(),
            ],
            body   : "grant_type=client_credentials&scope=read%3Adevice%3Acurrent_values&client_id=${clientId}&client_secret=${clientSecret}"
    ]

    try {
        httpPost(params) { response ->
            if (response.status < 300) {
                log.info "Access token received. HTTP response status ${response.status}"
                state.accessToken = response.data['access_token']
            } else {
                log.error "Failed to receive access token.  Response status code: ${response.status}, data: ${response.data}"
            }
        }
    } catch (Exception e) {
        log.error "Exception while requesting token: ${e}"
    }
}

void sendEventIfChanged(Map evt) {
    if (device.currentValue(evt.name).toString() != evt.value.toString()) {
        evt.isStateChange = true
        sendEvent(evt)
    }
}