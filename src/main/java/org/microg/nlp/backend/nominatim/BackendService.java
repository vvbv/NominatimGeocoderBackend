/*
 * Copyright 2013-2016 microG Project Team
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

package org.microg.nlp.backend.nominatim;

import android.content.Context;
import android.location.Address;
import android.net.Uri;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.microg.address.Formatter;
import org.microg.nlp.api.GeocoderBackendService;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static android.os.Build.VERSION.RELEASE;
import static org.microg.nlp.backend.nominatim.BuildConfig.VERSION_NAME;

public class BackendService extends GeocoderBackendService {
    private static final String TAG = "NominatimBackend";
    private static final String SERVICE_URL_MAPQUEST = "http://open.mapquestapi.com/nominatim/v1/";
    private static final String SERVICE_URL_OSM = "http://nominatim.openstreetmap.org/";
    private static final String REVERSE_GEOCODE_URL =
            "%sreverse?format=json&accept-language=%s&lat=%f&lon=%f";
    private static final String SEARCH_GEOCODE_URL =
            "%ssearch?format=json&accept-language=%s&addressdetails=1&bounded=1&q=%s&limit=%d";
    private static final String SEARCH_GEOCODE_WITH_BOX_URL =
            "%ssearch?format=json&accept-language=%s&addressdetails=1&bounded=1&q=%s&limit=%d" +
                    "&viewbox=%f,%f,%f,%f";
    private static final String WIRE_LATITUDE = "lat";
    private static final String WIRE_LONGITUDE = "lon";
    private static final String WIRE_ADDRESS = "address";
    private static final String WIRE_THOROUGHFARE = "road";
    private static final String WIRE_SUBLOCALITY = "suburb";
    private static final String WIRE_POSTALCODE = "postcode";
    private static final String WIRE_LOCALITY_CITY = "city";
    private static final String WIRE_LOCALITY_TOWN = "town";
    private static final String WIRE_LOCALITY_VILLAGE = "village";
    private static final String WIRE_SUBADMINAREA = "county";
    private static final String WIRE_ADMINAREA = "state";
    private static final String WIRE_COUNTRYNAME = "country";
    private static final String WIRE_COUNTRYCODE = "country_code";

    private Formatter formatter;

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            formatter = new Formatter();
        } catch (IOException e) {
            Log.w(TAG, "Could not initialize address formatter", e);
        }
    }

    @Override
    protected List<Address> getFromLocation(double latitude, double longitude, int maxResults,
                                            String locale) {
        String url = String.format(Locale.US, REVERSE_GEOCODE_URL, SERVICE_URL_OSM,
                locale.split("_")[0], latitude, longitude);
        try {
            JSONObject result = new JSONObject(new AsyncGetRequest(this,
                    url).asyncStart().retrieveString());
            Address address = parseResponse(localeFromLocaleString(locale), result);
            if (address != null) {
                List<Address> addresses = new ArrayList<>();
                addresses.add(address);
                return addresses;
            }
        } catch (Exception e) {
            Log.w(TAG, e);
        }
        return null;
    }

    private static Locale localeFromLocaleString(String localeString) {
        String[] split = localeString.split("_");
        if (split.length == 1) {
            return new Locale(split[0]);
        } else if (split.length == 2) {
            return new Locale(split[0], split[1]);
        } else if (split.length == 3) {
            return new Locale(split[0], split[1], split[2]);
        }
        throw new RuntimeException("That's not a locale: " + localeString);
    }

    @Override
    protected List<Address> getFromLocationName(String locationName, int maxResults,
                                                double lowerLeftLatitude, double lowerLeftLongitude, double upperRightLatitude,
                                                double upperRightLongitude, String locale) {
        String query = Uri.encode(locationName);
        String url;
        if (lowerLeftLatitude == 0 && lowerLeftLongitude == 0 && upperRightLatitude == 0 &&
                upperRightLongitude == 0) {
            url = String.format(Locale.US, SEARCH_GEOCODE_URL, SERVICE_URL_OSM,
                    locale.split("_")[0], query, maxResults);
        } else {
            url = String.format(Locale.US, SEARCH_GEOCODE_WITH_BOX_URL, SERVICE_URL_OSM,
                    locale.split("_")[0], query, maxResults, lowerLeftLongitude,
                    upperRightLatitude, upperRightLongitude, lowerLeftLatitude);
        }
        try {
            JSONArray result = new JSONArray(new AsyncGetRequest(this,
                    url).asyncStart().retrieveString());
            List<Address> addresses = new ArrayList<>();
            for (int i = 0; i < result.length(); i++) {
                Address address = parseResponse(localeFromLocaleString(locale),
                        result.getJSONObject(i));
                if (address != null)
                    addresses.add(address);
            }
            if (!addresses.isEmpty()) return addresses;
        } catch (Exception e) {
            Log.w(TAG, e);
        }
        return null;
    }

    private Address parseResponse(Locale locale, JSONObject result) throws JSONException {
        if (!result.has(WIRE_LATITUDE) || !result.has(WIRE_LONGITUDE) ||
                !result.has(WIRE_ADDRESS)) {
            return null;
        }
        Address address = new Address(locale);
        address.setLatitude(result.getDouble(WIRE_LATITUDE));
        address.setLongitude(result.getDouble(WIRE_LONGITUDE));

        JSONObject a = result.getJSONObject(WIRE_ADDRESS);

        address.setThoroughfare(a.optString(WIRE_THOROUGHFARE));
        address.setSubLocality(a.optString(WIRE_SUBLOCALITY));
        address.setPostalCode(a.optString(WIRE_POSTALCODE));
        address.setSubAdminArea(a.optString(WIRE_SUBADMINAREA));
        address.setAdminArea(a.optString(WIRE_ADMINAREA));
        address.setCountryName(a.optString(WIRE_COUNTRYNAME));
        address.setCountryCode(a.optString(WIRE_COUNTRYCODE));

        if (a.has(WIRE_LOCALITY_CITY)) {
            address.setLocality(a.getString(WIRE_LOCALITY_CITY));
        } else if (a.has(WIRE_LOCALITY_TOWN)) {
            address.setLocality(a.getString(WIRE_LOCALITY_TOWN));
        } else if (a.has(WIRE_LOCALITY_VILLAGE)) {
            address.setLocality(a.getString(WIRE_LOCALITY_VILLAGE));
        }

        if (formatter != null) {
            Map<String, String> components = new HashMap<>();
            for (String s : new IterableIterator<>(a.keys())) {
                components.put(s, String.valueOf(a.get(s)));
            }
            String[] split = formatter.formatAddress(components).split("\n");
            for (int i = 0; i < split.length; i++) {
                Log.d(TAG, split[i]);
                address.setAddressLine(i, split[i]);
            }

            address.setFeatureName(formatter.guessName(components));
        }

        return address;
    }

    private class IterableIterator<T> implements Iterable<T> {
        Iterator<T> i;

        public IterableIterator(Iterator<T> i) {
            this.i = i;
        }

        @Override
        public Iterator<T> iterator() {
            return i;
        }
    }

    private class AsyncGetRequest extends Thread {
        public static final String USER_AGENT = "User-Agent";
        public static final String USER_AGENT_TEMPLATE = "UnifiedNlp/%s (Linux; Android %s)";
        private final AtomicBoolean done = new AtomicBoolean(false);
        private final Context context;
        private final String url;
        private byte[] result;

        private AsyncGetRequest(Context context, String url) {
            this.context = context;
            this.url = url;
        }

        @Override
        public void run() {
            synchronized (done) {
                try {
                    Log.d(TAG, "Requesting " + url);
                    HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
                    connection.setRequestProperty(USER_AGENT, String.format(USER_AGENT_TEMPLATE, VERSION_NAME, RELEASE));
                    connection.setDoInput(true);
                    InputStream inputStream = connection.getInputStream();
                    result = readStreamToEnd(inputStream);
                } catch (Exception e) {
                    Log.w(TAG, e);
                }
                done.set(true);
                done.notifyAll();
            }
        }

        public AsyncGetRequest asyncStart() {
            start();
            return this;
        }

        public byte[] retrieveAllBytes() {
            if (!done.get()) {
                synchronized (done) {
                    while (!done.get()) {
                        try {
                            done.wait();
                        } catch (InterruptedException e) {
                            break;
                        }
                    }
                }
            }
            return result;
        }

        public String retrieveString() {
            return new String(retrieveAllBytes());
        }

        private byte[] readStreamToEnd(InputStream is) throws IOException {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            if (is != null) {
                byte[] buff = new byte[1024];
                while (true) {
                    int nb = is.read(buff);
                    if (nb < 0) {
                        break;
                    }
                    bos.write(buff, 0, nb);
                }
                is.close();
            }
            return bos.toByteArray();
        }
    }
}
