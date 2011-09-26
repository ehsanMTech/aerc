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
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.AccountManagerFuture;
import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;

/**
 * Performs authentication against of HTTP requests using a Google Account already
 *  present on the device.
 */
public class Authenticator {

    private String mCookie = null;
    private AccountManager mManager;
    private String mToken = null;
    private String mErrorMessage;
    private Context mContext;
    private URL mAppURI;
    private Account mAccount;

    /**
     * Creates a new Google App Engine authenticator for the app at the indicated URI using the indicated Account.
     * 
     * This constructor does not actually perform authentication, so it could in principle be called on the UI
     *  thread.  Authentication is performed lazily on the first call to authenticate(), or when explicitly 
     *  requested via setup(). Authentication is based on cookies provided by App Engine, which experience 
     *  suggests have a lifetime of about 24 hours, starting from the first call to setup().  Thus a single 
     *  Authenticator instance ought to be adequate to serve the needs of most REST dialogues.
     * 
     * @param activity Activity to be used, if necessary, to prompt for authentication
     * @param account Which android.accounts.Account to authenticate with
     * @param appURI For example, https://yourapp.appspot.com/
     */
    public static Authenticator appEngineAuthenticator(Context activity, Account account, URL appURI) {
        return new Authenticator(activity, account, appURI);
    }

    private Authenticator(Context activity, Account account, URL appURI) {
        mContext = (Activity) activity;
        mManager = AccountManager.get(activity);
        mAppURI = appURI;
        mAccount = account;
        // HTTP connection reuse which was buggy pre-froyo
        if (Integer.parseInt(Build.VERSION.SDK) < Build.VERSION_CODES.FROYO) {
            System.setProperty("http.keepAlive", "false");
        }
    }

    /**
     * Creates a new Google App Engine authenticator for the app at the indicated URI using an
     *  AccountManager authToken. The form of the authenticator is guaranteed never to interact with the user, 
     *  and as such is usable from a background thread, for example in a Service.   You can get an
     *  authToken via an AccountManager call, or by creating an Authenticator in a context where
     *  user interaction is acceptable, and getting a token with token() after having called setup().
     *  
     * @param context Used to retrieve strings for display
     * @param appURI
     * @param authToken
     */
    public static Authenticator appEngineAuthenticator(Context context, URL appURI, String authToken) {
        return new Authenticator(context, appURI, authToken);
    }

    private Authenticator(Context context, URL appURI, String authToken) {
        mAppURI = appURI;
        mToken = authToken;
        mContext = context;
        // HTTP connection reuse which was buggy pre-froyo
        if (Integer.parseInt(Build.VERSION.SDK) < Build.VERSION_CODES.FROYO) {
            System.setProperty("http.keepAlive", "false");
        }
    }

    /**
     * Return an error message decribing the problem, in the case that authentication failed.
     * @return the Error message
     */
    public String errorMessage() {
        return mErrorMessage;
    }

    /**
     * Add authentication information to an HttpURLConnection, before use.
     * 
     * @param connection The connection to enrich
     * @return whether or not the enrichment succeeded
     */
    public boolean authenticate(HttpURLConnection connection) {
        if (!setup())
            return false;

        connection.addRequestProperty("Cookie", mCookie);
        return true;
    }

    /**
     * Returns an Authentication token which has been set up and is ready for use.
     * @return the token, or null if authentication failed, in which case diagnostics are available
     *  via errorMessage().
     */
    public String token() {
        if (!setup())
            return null;
        return mToken;
    }

    private boolean setup() {

        // Authent is a 2-step process; first we have to get an auth token, then use it to get a cookie from 
        //  app engine, which is what gets added to future HTTP(S) requests.  Failing to get a token is not
        //  recoverable.  Failing to get a cookie *might* be a symptom of a cached token having expired, so in
        //  the interests of making an authenticator as long-lived as possible, we'll always invalidate the
        //  current token, and start with a fresh one.

        if (mCookie != null)
            return true;

        // TODO - clean up test mode
        if (mAppURI.toString().startsWith("http://192.168")) {
            mCookie = "Testing=TRUE";
            mToken = "whatever";
            return true;
        }
        mErrorMessage = null;

        // if we already have a token, though, that means we're a promise-not-to-prompt authenticator
        if (mToken == null) {
            if (!getToken(mAccount))
                return false;

            mManager.invalidateAuthToken("com.google", mToken);
            if (!getToken(mAccount))
                return false;
        }

        return getCookie(mAppURI);
    }

    private boolean getToken(Account account) {

        mToken = null;
        AccountManagerFuture<Bundle> result = mManager.getAuthToken(account, "ah", null, (Activity) mContext, null, null);
        try {
            Bundle bundle = result.getResult();
            mToken = bundle.getString(AccountManager.KEY_AUTHTOKEN);
        } catch (Exception e) {
            mErrorMessage = str(R.string.authentication_failed) + ": " + e.getClass() + " / " + e.getLocalizedMessage();
            for (StackTraceElement s : e.getStackTrace()) {
                mErrorMessage += s.toString() + "\n";
            }
        }

        if (mToken == null) {
            if (mErrorMessage == null)
                mErrorMessage = str(R.string.no_auth_token);
            return false;
        } else {
            return true;
        }
    }

    private boolean getCookie(URL uri) {
        String href = uri.toString();
        href = "https" + href.substring(href.indexOf(':')); // TLS please 
        if (!href.endsWith("/"))
            href = href + "/";
        href = href + "_ah/login?continue=http://localhost/&auth=" + mToken;
        HttpURLConnection conn = null;
        try {
            URL root = new URL(href);
            conn = (HttpURLConnection) root.openConnection();
            conn.setInstanceFollowRedirects(false);
            eatStream(new BufferedInputStream(conn.getInputStream()));
            List<String> cookies = conn.getHeaderFields().get("Set-Cookie");
            if (cookies == null) {
                cookies = conn.getHeaderFields().get("set-cookie");
            }
            if (cookies != null) {
                String cookieName = root.getProtocol().equals("https") ? "SACSID" : "ACSID";
                for (String cookie : cookies) {
                    if (cookie.startsWith(cookieName)) {
                        int semi = cookie.indexOf(';');
                        mCookie = (semi == -1) ? cookie : cookie.substring(0, semi);
                        break;
                    }
                }
            }
            if (mCookie == null) 
                mErrorMessage = str(R.string.authentication_failed) + ": " + str(R.string.no_cookie); 

        } catch (Exception e) {
            mErrorMessage = str(R.string.authentication_failed) + " " +
                    e.getClass().toString() + " / " + e.getLocalizedMessage();
            for (StackTraceElement s : e.getStackTrace()) {
                mErrorMessage += s.toString() + "\n";
            }
        } finally {
            if (conn != null)
                conn.disconnect();
        }
        return (mCookie != null);
    }

    // Back revs of Android sometimes fail to clean up if the response body is not read completely.
    private static void eatStream(InputStream in) 
            throws IOException {
        byte[] buf = new byte[1024];
        while (in.read(buf) != -1) 
            ; // empty
    }
    private String str(int id) {
        return mContext.getString(id);
    }
}
