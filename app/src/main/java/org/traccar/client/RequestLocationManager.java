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

import android.os.AsyncTask;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class RequestLocationManager {

    private static final int TIMEOUT = 15 * 1000;

    public interface RequestHandler {
        void onComplete(String Location);
    }

    private static class RequestAsyncTask extends AsyncTask<String, Void, String> {

        private RequestHandler handler;

        public RequestAsyncTask(RequestHandler handler) {
            this.handler = handler;
        }

        @Override
        protected String doInBackground(String... request) {
            return sendRequest(request[0]);
        }

        @Override
        protected void onPostExecute(String result) {
            handler.onComplete(result);
        }
    }

    public static String sendRequest(String request) {
        InputStream inputStream = null;
        try {
            URL url = new URL(request);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setReadTimeout(TIMEOUT);
            connection.setConnectTimeout(TIMEOUT);
            connection.connect();

            BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            String inputLine;
            StringBuffer response = new StringBuffer();

            while ((inputLine = in.readLine()) != null) {
                response.append(inputLine);
            }
            in.close();
            return response.toString();
        } catch (IOException error) {
            return "nan";
        } finally {
            try {
                if (inputStream != null) {
                    inputStream.close();
                }
            } catch (IOException secondError) {
                return "nan";
            }
        }
    }

    public static void sendRequestAsync(String request, RequestHandler handler) {
        RequestAsyncTask task = new RequestAsyncTask(handler);
        task.execute(request);
    }

}
