package com.textuality.aerc;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.AccountManagerCallback;
import android.accounts.AccountManagerFuture;
import android.app.Activity;
import android.content.Context;
import android.os.Bundle;

/**
 * Performs authentication against Google App Engine apps using a Google Account already
 *  present on the device.
 */
public class Authenticator {

    private String mCookie = null;
    private AccountManager mManager;
    private String mToken;
    private String mErrorMessage;
    private Activity mActivity;
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
    public Authenticator(Context activity, Account account, URL appURI) {
        mActivity = (Activity) activity;
        mManager = AccountManager.get(activity);
        mAppURI = appURI;
        mAccount = account;
    }
    
    /**
     * Creates a new Google App Engine authenticator for the app at the indicated URI using an
     *  AccountManager authToken. The form of the authenticator is guaranteed never to interact with the user, 
     *  and as such is usable from a background thread, for example in a Service.   You can get an
     *  authToken via an AccountManager call, or by creating an Authenticator in a context where
     *  user interaction is acceptable, and getting a token with token() after having called setup().
     *  
     * @param appURI
     * @param authToken
     */
    public Authenticator(URL appURI, String authToken) {
        mAppURI = appURI;
        mToken = authToken;
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

    /**
     * Set up for authentication using the account and API passed in the constructor.  Performs IO and 
     *  networking, thus can't be called on the UI thread.  May interact with the user to retrieve 
     *  ID and password. If not called explicitly, will be called lazily on the first call to authenticate()
     *  
     * @return Whether or not authentication succeeded; if false, errorMessage() provides an explanation
     */
    public boolean setup() {

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

    // Getting an auth token is an async process; you pass a callback, which gets called in another thread, 
    //  potentially quite a while later if the system decides it needs to prompt the user for authent.  Thus 
    //  the wait/notify dance here; callers' lives are easier if this call is made blocking/synchronous. 
    private Object mSync = new Object();
    private boolean getToken(Account account) {

        AccountManagerCallback<Bundle> tokenCallback =  new AccountManagerCallback<Bundle>() {
            public void run(AccountManagerFuture<Bundle> future) {
                try {
                    Bundle bundle = future.getResult();
                    mToken = bundle.getString(AccountManager.KEY_AUTHTOKEN);
                } catch (Exception e) {
                    mErrorMessage = str(R.string.authentication_failed) + ": " + e.getLocalizedMessage();
                } finally {
                    synchronized (mSync) {
                        mSync.notify();                            
                    }
                }
            }
        };

        try {
            synchronized(mSync) {
                mManager.getAuthToken(account, "ah", null, mActivity, tokenCallback, null);
                mSync.wait();
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
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
            String cookieName = root.getProtocol().equals("https") ? "SACSID" : "ACSID";
            for (String cookie : cookies) {
                if (cookie.startsWith(cookieName)) {
                    int semi = cookie.indexOf(';');
                    mCookie = (semi == -1) ? cookie : cookie.substring(0, semi);
                    break;
                }
            }
            if (mCookie == null) 
                mErrorMessage = str(R.string.authentication_failed) + ": " + str(R.string.no_cookie); 

        } catch (Exception e) {
            mErrorMessage = str(R.string.authentication_failed) + " " +
                    e.getClass().toString() + ":" + e.getLocalizedMessage();
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
        return mActivity.getString(id);
    }
}
