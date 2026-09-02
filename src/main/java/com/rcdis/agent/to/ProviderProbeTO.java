package com.rcdis.agent.to;

import java.util.List;

/**
 * Outcome of a connectivity probe against a model provider endpoint.
 *
 * <p>Returned by infrastructure clients instead of raw HTTP responses. Messages are sanitized: they
 * never contain API keys or full response bodies.</p>
 */
public record ProviderProbeTO(
        boolean success,
        String status,
        Integer httpStatus,
        long latencyMs,
        List<String> models,
        String message
) {

    /** Probe reached the endpoint and the credentials and model name were accepted. */
    public static final String STATUS_SUCCESS = "SUCCESS";
    /** Endpoint answered 401/403, so the API key is missing, wrong, or expired. */
    public static final String STATUS_AUTH_FAILED = "AUTH_FAILED";
    /** Endpoint answered 404, so the base URL or the request path is wrong. */
    public static final String STATUS_NOT_FOUND = "NOT_FOUND";
    /** Endpoint answered 429. */
    public static final String STATUS_RATE_LIMITED = "RATE_LIMITED";
    /** Endpoint answered 5xx. */
    public static final String STATUS_SERVER_ERROR = "SERVER_ERROR";
    /** Endpoint answered another 4xx status. */
    public static final String STATUS_CLIENT_ERROR = "CLIENT_ERROR";
    /** Request exceeded the configured timeout. */
    public static final String STATUS_TIMEOUT = "TIMEOUT";
    /** Host could not be resolved or the connection was refused. */
    public static final String STATUS_UNREACHABLE = "UNREACHABLE";
    /** Endpoint answered 2xx but the payload could not be parsed. */
    public static final String STATUS_INVALID_RESPONSE = "INVALID_RESPONSE";

    public static ProviderProbeTO success(int httpStatus, long latencyMs, List<String> models, String message) {
        return new ProviderProbeTO(true, STATUS_SUCCESS, httpStatus, latencyMs, models, message);
    }

    public static ProviderProbeTO failure(String status, Integer httpStatus, long latencyMs, String message) {
        return new ProviderProbeTO(false, status, httpStatus, latencyMs, List.of(), message);
    }
}
