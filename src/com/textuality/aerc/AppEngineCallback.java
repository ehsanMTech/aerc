package com.textuality.aerc;

import java.util.List;
import java.util.Map;

/**
 * Interface that AppEngineClient uses to call back to report progress, errors, and completion
 *  for the backgroundGet() and backgroundPost() methods of AppEngineClient.
 */
public interface AppEngineCallback {

    /**
     * Reports a problem.  Possibilities include authentication failure, and a network IO error.
     *  HTTP-level problems, e.g. 500s and 404s, are reported through the "done" call.
     *  
     * @param why A description of the problem
     */
    public void reportError(String why);
    
    /**
     * Reports progress in processing the GET or POST.
     *  Called on the thread where the request was launched.
     *  
     * @param message Cheerful and encouraging news from the front.
     */
    public void reportProgress(String message);
    
    /**
     * Reports completion of a GET or POST request.
     *  Called on the thread where the request was launched.  If an error was reported with
     *  "reportError", this won't be called.
     *  
     * @param status HTTP status code. 
     * @param headers All the headers received with the response
     * @param body The response body, if any
     */
    public void done(int status, Map<String, List<String>> headers, byte[] body);

}
