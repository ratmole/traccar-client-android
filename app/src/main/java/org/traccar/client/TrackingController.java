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
import android.os.Build;
import android.os.Handler;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

public class TrackingController implements PositionProvider.PositionListener, NetworkManager.NetworkHandler {

    private static final String TAG = TrackingController.class.getSimpleName();
    private static final int RETRY_DELAY = 30 * 1000;
    private static final int WAKE_LOCK_TIMEOUT = 120 * 1000;

    private boolean isOnline;
    private boolean isWaiting;

    private Context context;
    private Handler handler;
    private SharedPreferences preferences;

    private String url, api;
    private Position oldDbId;

    String cellidOld, celllacOld;
    Double latOld, lonOld;

    private PositionProvider positionProvider;
    private DatabaseHelper databaseHelper;
    private NetworkManager networkManager;

    private PowerManager.WakeLock wakeLock;

    private void lock() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.GINGERBREAD_MR1) {
            wakeLock.acquire();
        } else {
            wakeLock.acquire(WAKE_LOCK_TIMEOUT);
        }
    }

    private void unlock() {
        if (wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    public TrackingController(Context context) {
        this.context = context;
        handler = new Handler();
        preferences = PreferenceManager.getDefaultSharedPreferences(context);
        api = preferences.getString(MainActivity.KEY_API, null);

        if (preferences.getString(MainActivity.KEY_PROVIDER, "gps").equals("mixed")) {
            positionProvider = new MixedPositionProvider(context, this);
        } else {
            positionProvider = new SimplePositionProvider(context, this);
        }
        databaseHelper = new DatabaseHelper(context);
        networkManager = new NetworkManager(context, this);
        isOnline = networkManager.isOnline();

        url = preferences.getString(MainActivity.KEY_URL, null);

        PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, getClass().getName());
    }

    public void start() {
        if (isOnline) {
            read();
        }
        try {
            positionProvider.startUpdates();
        } catch (SecurityException e) {
            Log.w(TAG, e);
        }
        networkManager.start();
    }

    public void stop() {
        networkManager.stop();
        try {
            positionProvider.stopUpdates();
        } catch (SecurityException e) {
            Log.w(TAG, e);
        }
        handler.removeCallbacksAndMessages(null);
    }

    @Override
    public void onPositionUpdate(Position position) {
        StatusActivity.addMessage(context.getString(R.string.status_location_update));
        if (position != null) {
            write(position);
        }
    }

    @Override
    public void onNetworkUpdate(boolean isOnline) {
        StatusActivity.addMessage(context.getString(R.string.status_connectivity_change));
        if (!this.isOnline && isOnline) {
            read();
        }
        this.isOnline = isOnline;
    }

    //
    // State transition examples:
    //
    // write -> read -> send -> delete -> read
    //
    // read -> send -> retry -> read -> send
    //

    private void log(String action, Position position) {
        if (position != null) {
            if (position.getGsm().equals(0)) {
                action += " (" +
                        "id:" + position.getId() +
                        " gsm:" + position.getGsm() +
                        " time:" + position.getTime().getTime() / 1000 +
                        " lat:" + position.getLatitude() +
                        " lon:" + position.getLongitude() + ")";
            }else {
                action += " (" +
                        "id:" + position.getId() +
                        " gsm:" + position.getGsm() +
                        " time:" + position.getTime().getTime() / 1000 +
                        " cid:" + position.getLatitude() +
                        " lac:" + position.getLongitude() + ")";
            }
        }
        Log.d(TAG, action);
    }

    private void write(Position position) {
        log("write", position);
        lock();
        databaseHelper.insertPositionAsync(position, new DatabaseHelper.DatabaseHandler<Void>() {
            @Override
            public void onComplete(boolean success, Void result) {
                if (success) {
                    if (isOnline && isWaiting) {
                        read();
                        isWaiting = false;
                    }
                }
                unlock();
            }
        });
    }

    private void read() {
        log("read", null);
        lock();
        databaseHelper.selectPositionAsync(new DatabaseHelper.DatabaseHandler<Position>() {
            @Override
            public void onComplete(boolean success, Position result) {
                if (success) {
                    if (result != null) {
                        if (result.getDeviceId().equals(preferences.getString(MainActivity.KEY_DEVICE, null))) {
                            if (result.getGsm() == 1) {
                                getLocation(result, String.valueOf(result.getLatitude()), String.valueOf(result.getLongitude()));
                            } else {
                                send(result);
                            }
                        } else {
                            delete(result);
                        }
                    } else {
                        isWaiting = true;
                    }
                } else {
                    retry();
                }
                unlock();
            }
        });
    }

    public void delete(Position position) {
        log("delete", position);
        lock();
        databaseHelper.deletePositionAsync(position.getId(), new DatabaseHelper.DatabaseHandler<Void>() {
            @Override
            public void onComplete(boolean success, Void result) {
                if (success) {
                    read();
                } else {
                    retry();
                }
                unlock();
            }
        });
    }

    private void send(final Position position) {
        log("send", position);
        lock();
        String request = ProtocolFormatter.formatRequest(url, position);
        RequestManager.sendRequestAsync(request, new RequestManager.RequestHandler() {
            @Override
            public void onComplete(boolean success) {
                if (success) {
                    delete(position);
                } else {
                    StatusActivity.addMessage(context.getString(R.string.status_send_fail));
                    retry();
                }
                unlock();
            }
        });
    }

    private void retry() {
        log("retry", null);
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isOnline) {
                    read();
                }
            }
        }, RETRY_DELAY);
    }

    private void getLocation(final Position positionC, final String cellid, final String celllac) {

        if (cellid != null && (cellidOld == null || !cellid.equalsIgnoreCase(cellidOld))
                || (celllac != null && (celllacOld == null || !celllac.equalsIgnoreCase(celllacOld)))) {

            Log.i(TAG, "OpenCellid new");

            TelephonyManager telephonyManager = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            String networkOperator = telephonyManager.getNetworkOperator();

            int mcc = Integer.parseInt(networkOperator.substring(0, 3));
            int mnc = Integer.parseInt(networkOperator.substring(3));

            final String request = "https://www.opencellid.org/cell/get?mcc=" + mcc + "&mnc=" + mnc + "&cellid=" + cellid + "&lac=" + celllac + "&key=" + api + "&format=json";

            RequestLocationManager.sendRequestAsync(request, new RequestLocationManager.RequestHandler() {
                @Override
                public void onComplete(String location) {
                    if (!location.equalsIgnoreCase("nan")) {
                        try {
                            JSONObject jsonResponse = new JSONObject(location.toString());

                            Location targetLocation = new Location("");
                            targetLocation.setLatitude(Double.parseDouble(jsonResponse.getString("lat")));//your coords of course
                            targetLocation.setLongitude(Double.parseDouble(jsonResponse.getString("lon")));

                            positionC.setLatitude(targetLocation.getLatitude());
                            positionC.setLongitude(targetLocation.getLongitude());

                            send(positionC);

                            cellidOld = cellid;
                            celllacOld = celllac;

                            latOld = Double.parseDouble(jsonResponse.getString("lat"));
                            lonOld = Double.parseDouble(jsonResponse.getString("lon"));

                        } catch (JSONException e) {
                            StatusActivity.addMessage("OpenCellid Error");
                            Toast.makeText(context, "OpenCellid Error, Please Check Your Api Key", Toast.LENGTH_SHORT).show();
                            delete(positionC);
                            e.printStackTrace();
                        }

                    }
                }
            });
        } else {
            Log.i(TAG, "OpenCellid old");

            Location targetLocation = new Location("");
            targetLocation.setLatitude(latOld);
            targetLocation.setLongitude(lonOld);

            positionC.setLatitude(targetLocation.getLatitude());
            positionC.setLongitude(targetLocation.getLongitude());

            send(positionC);

        }

    }

}
