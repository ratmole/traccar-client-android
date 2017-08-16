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
import android.location.GpsStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.telephony.TelephonyManager;
import android.telephony.gsm.GsmCellLocation;
import android.util.Log;

@SuppressWarnings("MissingPermission")
public class MixedPositionProvider extends PositionProvider implements LocationListener, GpsStatus.Listener {

    private static final int FIX_TIMEOUT = 30 * 1000;

    private LocationListener backupListener;
    private long lastFixTime;
    private Handler handler;


    public MixedPositionProvider(Context context, PositionListener listener) {
        super(context, listener);
        handler = new Handler();

    }

    public void startUpdates() {
        lastFixTime = System.currentTimeMillis();
        locationManager.addGpsStatusListener(this);
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, requestInterval, 0, this);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, e);
        }
    }

    public void stopUpdates() {
        locationManager.removeUpdates(this);
        locationManager.removeGpsStatusListener(this);
        stopBackupProvider();
    }

    private void startBackupProvider() {
        Log.i(TAG, "backup provider start");

        LocationManager lm = (LocationManager)context.getSystemService(Context.LOCATION_SERVICE);

        boolean gps_enabled = false;
        boolean network_enabled = false;

        try {
            gps_enabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER);
        } catch(Exception ex) {}

        try {
            network_enabled = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch(Exception ex) {}

        //Log.i(TAG, "gps_enabled "+gps_enabled);
        // Log.i(TAG, "network_enabled "+network_enabled);

        if (!gps_enabled && !network_enabled) {
            startUpdatesGsm();
        } else {
            stopUpdatesGsm();
            if (backupListener == null) {

                backupListener = new LocationListener() {
                    @Override
                    public void onLocationChanged(Location location) {
                        Log.i(TAG, "backup provider location");
                        if (System.currentTimeMillis() - lastFixTime < requestInterval) {
                            Log.i(TAG, "location forced");
                            updateLocation(location,true);
                        } else {
                            updateLocation(location,false);
                        }
                        lastFixTime = System.currentTimeMillis();
                    }

                    @Override
                    public void onStatusChanged(String s, int i, Bundle bundle) {
                    }

                    @Override
                    public void onProviderEnabled(String s) {
                        stopUpdatesGsm();
                    }

                    @Override
                    public void onProviderDisabled(String s) {


                    }
                };

                locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER, requestInterval, 0, backupListener);
            }
        }
    }

    public void stopBackupProvider() {
        stopUpdatesGsm();
        Log.i(TAG, "backup provider stop");
        if (backupListener != null) {
            locationManager.removeUpdates(backupListener);
            backupListener = null;
        }
    }

    @Override
    public void onLocationChanged(Location location) {
        Log.i(TAG, "provider location");
        stopBackupProvider();
        lastFixTime = System.currentTimeMillis();
        updateLocation(location, false);
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
    }

    @Override
    public void onProviderEnabled(String provider) {
        Log.i(TAG, "provider enabled");
        startBackupProvider();
    }

    @Override
    public void onProviderDisabled(String provider) {
        Log.i(TAG, "provider disabled");
        startBackupProvider();
    }

    @Override
    public void onGpsStatusChanged(int event) {

        if (backupListener == null && System.currentTimeMillis() - lastFixTime - requestInterval > FIX_TIMEOUT) {
                startBackupProvider();
            }
    }

    public void startUpdatesGsm() {
            try {


                TelephonyManager telephonyManager = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
                GsmCellLocation cellLocation = (GsmCellLocation) telephonyManager.getCellLocation();

                Location targetLocation = new Location("");
                targetLocation.setLatitude(Double.parseDouble(String.valueOf(cellLocation.getCid())));
                targetLocation.setLongitude(Double.parseDouble(String.valueOf(cellLocation.getLac())));
                targetLocation.setTime(System.currentTimeMillis());

                Bundle gsm = new Bundle();
                gsm.putSerializable("gsm", 1);
                targetLocation.setExtras(gsm);

                if (System.currentTimeMillis() - lastFixTime < requestInterval) {
                    Log.i(TAG, "location forced");
                    updateLocation(targetLocation,true);
                } else {
                    updateLocation(targetLocation, false);
                }

                lastFixTime = System.currentTimeMillis();
                retryGsm();

            } catch (IllegalArgumentException e) {
                Log.w(TAG, e);
                retryGsm();
            }

    }

    public void stopUpdatesGsm() {
        handler.removeCallbacksAndMessages(null);
    }

        private void retryGsm() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                startUpdatesGsm();
            }
        }, interval);
    }

}
