/**
 *
 *   File: AirThingsForHubitat.groovy
 *
 *  Copyright 2022 Nick M
 *
 *  To use:
 *     - Register AirThings app and get the Client ID and Client Secret.  Follow https://developer.airthings.com/docs/api-getting-started/index.html
 *     - From the AirThings dashboard https://dashboard.airthings.com/devices get the ID of the device
 *     - Use the values in preferences
 *
 *  Notes:
 *     AirThings API token for consumers has only one scope - `read:device:current_values`,
 *     as a result there is only one method that can be called - `https://ext-api.airthings.com/v1/devices/{serialNumber}/latest-samples`
 *
 *  References:
 *     - Repository: https://github.com/Nickolaim/AirThingsForHubitat/
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

static String userAgent() { 'AirThingsForHubitat/1.0.0' }

metadata {
    definition(name: 'AirThingsForHubitat', namespace: 'nickolaim', author: 'Nick M') {
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

void requestSensorData() {
    boolean data = requestSensorDataWithToken()
    if (!data) {
        // If failed - refresh token and retry
        requestAccessToken()
        requestSensorDataWithToken()
    }
}

void sendEventUpdateTile(Map data, String airThingsKey, String eventName, String onScreenName,
                         String unit, StringBuilder sb, DecimalFormat formatter) {
    if (data.containsKey(airThingsKey)) {
        def value = data[airThingsKey]
        sendEventIfChanged(name: eventName, value: value)
        sb.append("<tr><td>${onScreenName}</td><td style='background-color: rgba(0, 255, 0, 0.0);'>")
        sb.append("${formatter.format(value)} ${unit}</td></tr>")
    }
}

boolean requestSensorDataWithToken() {
    try {
        httpGet([
                'uri'    : "https://ext-api.airthings.com/v1/devices/${serialNumber}/latest-samples",
                'headers': [
                        'User-Agent'   : userAgent(),
                        'Authorization': "Bearer ${state?.accessToken}"
                ],
        ]) { response ->
            if (response.status < 300) {
                DecimalFormat decimalFormatter = new DecimalFormat("###.0")
                DecimalFormat integerFormatter = new DecimalFormat("###")

                Map data = response.data['data']
                StringBuilder sb = new StringBuilder('<table style="display:inline;font-size:70%">')

                sendEventUpdateTile(data, 'co2', 'carbonDioxide', 'CO2', 'ppm', sb, integerFormatter)
                sendEventUpdateTile(data, 'voc', 'voc', 'VOC', 'ppb', sb, integerFormatter)
                sendEventUpdateTile(data, 'radonShortTermAvg', 'radonShortTermAvg', 'Radon', 'pCi/L', sb, decimalFormatter)
                sendEventUpdateTile(data, 'humidity', 'humidity', 'Humidity', '%rh', sb, integerFormatter)
                sendEventUpdateTile(data, 'temp', 'temperature', 'Temp', 'C', sb, decimalFormatter)
                sendEventUpdateTile(data, 'pm25', 'airQualityPM25', 'PM 2.5', 'µg/m³', sb, integerFormatter)
                sendEventUpdateTile(data, 'pm1', 'airQualityPM1', 'PM 1', 'µg/m³', sb, integerFormatter)
                sendEventUpdateTile(data, 'pressure', 'pressure', 'Pressure', 'hPa', sb, integerFormatter)
                sendEventUpdateTile(data, 'battery', 'battery', 'Battery', '%', sb, integerFormatter)

                sb.append('</table')
                sendEventIfChanged(name: 'tile', value: sb.toString())

            } else {
                log.error "Failed to receive samples.  Response status code: ${response.status}, data: ${response.data}"
                return false
            }
        }

        return true
    } catch (Exception e) {
        log.error "Exception while getting sensor data: ${e}"
        log.error e.getStackTrace()
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
        log.error e.getStackTrace()
    }
}

void sendEventIfChanged(Map evt) {
    if (device.currentValue(evt.name).toString() != evt.value.toString()) {
        evt.isStateChange = true
        sendEvent(evt)
    }
}
