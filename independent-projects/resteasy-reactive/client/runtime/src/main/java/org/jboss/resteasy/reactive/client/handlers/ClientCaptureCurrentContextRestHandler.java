package org.jboss.resteasy.reactive.client.handlers;

import java.util.ArrayList;
import java.util.List;

import org.jboss.resteasy.reactive.client.impl.ClientRequestContextImpl;
import org.jboss.resteasy.reactive.client.impl.RestClientRequestContext;
import org.jboss.resteasy.reactive.client.spi.ClientRestHandler;

/**
 * This handler is meant to be executed first in the handler chain and it captures some useful information like caller
 * stacktrace.
 */
public class ClientCaptureCurrentContextRestHandler implements ClientRestHandler {

    private static final String RESTEASY_REACTIVE_PACKAGE = "org.jboss.resteasy.reactive";
    private static final String AUTOGENERATED_TAG = "$$";

    @Override
    public void handle(RestClientRequestContext requestContext) throws Exception {
        ClientRequestContextImpl clientRequestContext = requestContext.getClientRequestContext();
        if (clientRequestContext == null) {
            return;
        }

        captureCallerStackTrace(clientRequestContext);
    }

    private void captureCallerStackTrace(ClientRequestContextImpl clientRequestContext) {
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
        List<StackTraceElement> effectiveStackTrace = new ArrayList<>(stackTrace.length);
        boolean foundUserMethod = false;
        // skip first trace which is Thread.getStackTrace
        for (int i = 1; i < stackTrace.length; i++) {
            StackTraceElement trace = stackTrace[i];
            if (foundUserMethod) {
                effectiveStackTrace.add(trace);
            } else if (!trace.getClassName().startsWith(RESTEASY_REACTIVE_PACKAGE)
                    && !trace.getClassName().contains(AUTOGENERATED_TAG)) {
                // Skip the latest traces that starts with the "org.jboss.resteasy.reactive" package,
                effectiveStackTrace.add(trace);
                foundUserMethod = true;
            }
        }

        clientRequestContext.getRestClientRequestContext()
                .setCallerStackTrace(effectiveStackTrace.toArray(new StackTraceElement[0]));
    }
}