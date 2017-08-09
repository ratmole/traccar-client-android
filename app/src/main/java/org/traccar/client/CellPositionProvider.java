/*
 * Copyright 2015 Anton Tananaev (anton.tananaev@gmail.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.client;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import android.location.LocationListener;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.telephony.TelephonyManager;
import android.telephony.gsm.GsmCellLocation;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

@SuppressWarnings("MissingPermission")
public class CellPositionProvider extends PositionProvider implements LocationListener {

    private Handler handler;
    String cellidOld, celllacOld;
    Double latOld,lonOld;

    SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);

    public CellPositionProvider(Context context, PositionListener listener) {
        super(context, listener);

        handler = new Handler();
        interval = Long.parseLong(preferences.getString(MainActivity.KEY_INTERVAL, null)) * 1000;
        deviceId = preferences.getString(MainActivity.KEY_DEVICE, null);
        api = preferences.getString(MainActivity.KEY_API, null);

    }

    public void startUpdates() {
        try {
            TelephonyManager telephonyManager = (TelephonyManager)context.getSystemService(Context.TELEPHONY_SERVICE);
            GsmCellLocation cellLocation = (GsmCellLocation)telephonyManager.getCellLocation();

            String networkOperator = telephonyManager.getNetworkOperator();

            int mcc = Integer.parseInt(networkOperator.substring(0, 3));
            int mnc = Integer.parseInt(networkOperator.substring(3));

            getLocation(String.valueOf(mcc), String.valueOf(mnc), String.valueOf(cellLocation.getCid()), String.valueOf(cellLocation.getLac()));

            retry();

        } catch (IllegalArgumentException e) {
            Log.w(TAG, e);
            retry();
        }
    }

    public void stopUpdates() {
        handler.removeCallbacksAndMessages(null);
    }

    @Override
    public void onLocationChanged(Location location) {
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
    }

    @Override
    public void onProviderEnabled(String provider) {
    }

    @Override
    public void onProviderDisabled(String provider) {
    }

    private void getLocation(String mmc, String mnc, final String cellid,  final String celllac) {

        if (cellid != null && (cellidOld == null || !cellid.equalsIgnoreCase(cellidOld))
            ||  (celllac != null && (celllacOld == null || !celllac.equalsIgnoreCase(celllacOld))) ) {

            Log.i(TAG, "OpenCellid new");


            final String request = "https://www.opencellid.org/cell/get?mcc=" + mmc + "&mnc=" + mnc + "&cellid=" + cellid + "&lac=" + celllac + "&key=" + api + "&format=json";
            RequestLocationManager.sendRequestAsync(request, new RequestLocationManager.RequestHandler() {
                @Override
                public void onComplete(String location) {
                    if (!location.equalsIgnoreCase("nan")) {
                        try {
                            JSONObject jsonResponse = new JSONObject(location.toString());

                            Location targetLocation = new Location("");//provider name is unnecessary
                            targetLocation.setLatitude(Double.parseDouble(jsonResponse.getString("lat")));//your coords of course
                            targetLocation.setLongitude(Double.parseDouble(jsonResponse.getString("lon")));
                            targetLocation.setTime(System.currentTimeMillis());

                            updateLocation(targetLocation);
                            cellidOld = cellid;
                            celllacOld = celllac;
                            latOld = Double.parseDouble(jsonResponse.getString("lat"));
                            lonOld = Double.parseDouble(jsonResponse.getString("lon"));


                        } catch (JSONException e) {
                            StatusActivity.addMessage("OpenCellid Error");
                            e.printStackTrace();
                        }

                    } else {
                        StatusActivity.addMessage(context.getString(R.string.status_send_fail));
                        retry();
                    }
                }
            });
        } else {
            Log.i(TAG, "OpenCellid old");
            Location targetLocation = new Location("");//provider name is unnecessary
            targetLocation.setLatitude(latOld);//your coords of course
            targetLocation.setLongitude(lonOld);
            targetLocation.setTime(System.currentTimeMillis());
            updateLocation(targetLocation);
        }
    }

    private void retry() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                startUpdates();
            }
        }, interval);
    }
}
