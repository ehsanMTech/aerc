/*
 * Copyright 2011 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.textuality.aerc;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Map;

import android.accounts.Account;
import android.content.Context;
import android.os.AsyncTask;

/**
 * Performs GET and POST operations against a Google App Engine app, authenticating with
 *  a Google account on the device.
 */
public class AppEngineClient {

    private Authenticator mAuthenticator;
    private Context mContext;
    private String mErrorMessage;

    /**
     * Constructs a REST client, which may be used for multiple requests.  This constructor may be 
     *  called on the UI thread.
     * 
     * @param appURI The URI of the App Engine app you want to interact with, e.g. your-app.appspot.com
     * @param account The android.accounts.Account representing the Google account to authenticate with.
     * @param context Used, if necessary, to prompt user for authentication
     */
    public AppEngineClient(URL appURI, Account account, Context context) {
        mAuthenticator = Authenticator.appEngineAuthenticator(context, account, appURI);
        mContext = context;
    }
    
    /**
     * Constructs a REST client, which may be used for multiple requests, and which will never prompt
     *  the user for authentication input.  This constructor may be called on the UI thread but is 
     *  suitable for use on background threads with no access to an Activity. 
     * 
     * @param appURI The URI of the App Engine app you want to interact with, e.g. your-app.appspot.com
     * @param authToken AccountManager authtoken, e.g. from the token() method 
     * @param context Used to look up strings
     */
    public AppEngineClient(URL appURI, String authToken, Context context) {
        mAuthenticator = Authenticator.appEngineAuthenticator(context, appURI, authToken);
        mContext = context;
    }

    /**
     * Performs an HTTP POST request in the background.  This is handled by an AsyncTask and thus may
     *  be called from the UI thread.
     *  
     * @param uri The URI you're sending the POST to
     * @param headers Any extra HTTP headers you want to send; may be null
     * @param body The bytes to POST
     * @param callback Receives progress and completion reports
     */
    public void backgroundPost(URL uri, Map<String, List<String>> headers, byte[] body, AppEngineCallback callback) {
        (new Worker()).execute(new POST(uri, headers, body, callback));
    }

    /**
     * Performs an HTTP GET request in the background.  This is handled by an AsyncTask and thus may
     *  be called from the UI thread.
     * 
     * @param uri The URI you're sending the GET to
     * @param headers Any extra HTTP headers you want to send; may be null
     * @param callback Receives progress and completion reports
     */
    public void backgroundGet(URL uri, Map<String, List<String>> headers, AppEngineCallback callback) {
        (new Worker()).execute(new GET(uri, headers, callback));
    }

    /**
     * Performs an HTTP GET request.  The request is performed inline and this method must not
     *  be called from the UI thread.
     *  
     * @param uri The URI you're sending the GET to
     * @param headers Any extra HTTP headers you want to send; may be null
     * @return a Response structure containing the status, headers, and body. Returns null if the request 
     *   could not be launched due to an IO error or authentication failure; in which case use errorMessage()
     *   to retrieve diagnostic information.
     */
    public Response get(URL uri, Map<String, List<String>> headers) {
        mErrorMessage = null;
        if (!mAuthenticator.setup()) {
            mErrorMessage = mAuthenticator.errorMessage();
            return null;
        }
        GET get = new GET(uri, headers, null);
        return getOrPost(get, null);
    }

    /**
     * Performs an HTTP POST request.  The request is performed inline and this method must not
     *  be called from the UI thread.
     *  
     * @param uri The URI you're sending the GET to
     * @param headers Any extra HTTP headers you want to send; may be null
     * @param body The request body to transmit
     * @return a Response structure containing the status, headers, and body. Returns null if the request 
     *   could not be launched due to an IO error or authentication failure; in which case use errorMessage()
     *   to retrieve diagnostic information.
     */
    public Response post(URL uri, Map<String, List<String>> headers, byte[] body) {
        mErrorMessage = null;
        if (!mAuthenticator.setup()) {
            mErrorMessage = mAuthenticator.errorMessage();
            return null;
        }
        POST post = new POST(uri, headers, body, null);
        return getOrPost(post, null);
    }

    /**
     * Provides an error message should a get() or post() return null.
     * @return the message
     */
    public String errorMessage() {
        return mErrorMessage;
    }

    private Response getOrPost(Request request, Reporter reporter) {
        HttpURLConnection conn = null;
        Response response = null;
        report(reporter, str(R.string.sending_request));
        try {
            conn = (HttpURLConnection) request.uri.openConnection();
            if (!mAuthenticator.authenticate(conn)) {
                mErrorMessage = str(R.string.authentication_failed) + ": " + mAuthenticator.errorMessage();
            } else {
                if (request.headers != null) {
                    for (String header : request.headers.keySet()) {
                        for (String value : request.headers.get(header)) {
                            conn.addRequestProperty(header, value);
                        }
                    }
                }
                if (request instanceof POST) {
                    byte[] payload = ((POST) request).body; 
                    conn.setDoOutput(true);
                    conn.setFixedLengthStreamingMode(payload.length);
                    conn.getOutputStream().write(payload);
                    report(reporter, str(R.string.sent) + " " + payload.length + " " + str(R.string.bytes));
                }
                BufferedInputStream in = new BufferedInputStream(conn.getInputStream());
                report(reporter, str(R.string.receiving_response));
                byte[] body = readStream(in);
                report(reporter, str(R.string.received) + " " + body.length + " " + str(R.string.bytes));
                response = new Response(conn.getResponseCode(), conn.getHeaderFields(), body);
            }
        } catch (IOException e) {
            e.printStackTrace(System.err);
            mErrorMessage = ((request instanceof POST) ? "POST " : "GET ") +
                    str(R.string.failed) + ": " + e.getLocalizedMessage();
        } finally {
            if (conn != null)
                conn.disconnect();
        }
        return response;
    }

    // Have to make a new AsyncTask & HttpURLConnection for each request
    private class Worker extends AsyncTask<Request, String, Response> implements Reporter {

        private AppEngineCallback mCustomer;

        @Override
        protected Response doInBackground(Request... params) {
            Request request = params[0];
            mCustomer = request.callback;
            return getOrPost(request, this);
        }

        public void report(String message) {
            publishProgress(message);
        }

        @Override
        protected void onProgressUpdate(String... values) {
            mCustomer.reportProgress(values[0]);
        }

        @Override
        protected void onPostExecute(Response response) {
            if (response == null) 
                mCustomer.reportError(mErrorMessage);
            else 
                mCustomer.done(response.status, response.headers, response.body);
        }
    }

    // request structs
    private class Request {
        public URL uri;
        public Map<String, List<String>> headers;
        public AppEngineCallback callback;
        public Request(URL uri, Map<String, List<String>> headers, AppEngineCallback callback) {
            this.uri = uri; this.headers = headers; this.callback = callback;
        }
    }
    private class POST extends Request {
        public byte[] body;
        public POST(URL uri, Map<String, List<String>> headers, byte[] body, AppEngineCallback callback) {
            super(uri, headers, callback);
            this.body = body; 
        }
    }
    private class GET extends Request {
        public GET(URL uri, Map<String, List<String>> headers, AppEngineCallback callback) {
            super(uri, headers, callback);
        }
    }

    // utilities
    private static byte[] readStream(InputStream in) 
            throws IOException {
        byte[] buf = new byte[1024];
        int count = 0;
        ByteArrayOutputStream out = new ByteArrayOutputStream(1024);
        while ((count = in.read(buf)) != -1) 
            out.write(buf, 0, count);
        return out.toByteArray();
    }

    private interface Reporter { 
        public void report(String message); 
    }

    private String str(int id) {
        return mContext.getString(id);
    }
    private void report(Reporter reporter, String message) {
        if (reporter != null)
            reporter.report(message);
    }
}



