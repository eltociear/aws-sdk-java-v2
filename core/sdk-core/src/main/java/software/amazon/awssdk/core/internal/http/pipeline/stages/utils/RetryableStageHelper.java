/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.awssdk.core.internal.http.pipeline.stages.utils;

import java.time.Duration;
import java.util.OptionalDouble;
import java.util.concurrent.CompletionException;
import software.amazon.awssdk.annotations.SdkInternalApi;
import software.amazon.awssdk.core.Response;
import software.amazon.awssdk.core.SdkStandardLogger;
import software.amazon.awssdk.core.client.config.SdkClientOption;
import software.amazon.awssdk.core.exception.NonRetryableException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.interceptor.ExecutionAttribute;
import software.amazon.awssdk.core.internal.InternalCoreExecutionAttribute;
import software.amazon.awssdk.core.internal.http.HttpClientDependencies;
import software.amazon.awssdk.core.internal.http.RequestExecutionContext;
import software.amazon.awssdk.core.internal.http.pipeline.stages.AsyncRetryableStage;
import software.amazon.awssdk.core.internal.http.pipeline.stages.RetryableStage;
import software.amazon.awssdk.core.internal.retry.ClockSkewAdjuster;
import software.amazon.awssdk.core.internal.retry.RateLimitingTokenBucket;
import software.amazon.awssdk.core.metrics.CoreMetric;
import software.amazon.awssdk.core.retry.RetryMode;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.core.retry.RetryPolicyContext;
import software.amazon.awssdk.core.retry.RetryUtils;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpResponse;

/**
 * Contains the logic shared by {@link RetryableStage} and {@link AsyncRetryableStage} when querying and interacting with a
 * {@link RetryPolicy}.
 */
@SdkInternalApi
public class RetryableStageHelper {
    public static final String SDK_RETRY_INFO_HEADER = "amz-sdk-request";

    public static final ExecutionAttribute<Duration> LAST_BACKOFF_DELAY_DURATION =
        new ExecutionAttribute<>("LastBackoffDuration");

    private final SdkHttpFullRequest request;
    private final RequestExecutionContext context;
    private final RetryPolicy retryPolicy;
    private final RateLimitingTokenBucket rateLimitingTokenBucket;
    private final HttpClientDependencies dependencies;

    private int attemptNumber = 0;
    private SdkHttpResponse lastResponse = null;
    private SdkException lastException = null;

    public RetryableStageHelper(SdkHttpFullRequest request,
                                RequestExecutionContext context,
                                RateLimitingTokenBucket rateLimitingTokenBucket,
                                HttpClientDependencies dependencies) {
        this.request = request;
        this.context = context;
        this.retryPolicy = dependencies.clientConfiguration().option(SdkClientOption.RETRY_POLICY);
        this.rateLimitingTokenBucket = rateLimitingTokenBucket;
        this.dependencies = dependencies;
    }

    /**
     * Invoke when starting a request attempt, before querying the retry policy.
     */
    public void startingAttempt() {
        ++attemptNumber;
        context.executionAttributes().putAttribute(InternalCoreExecutionAttribute.EXECUTION_ATTEMPT, attemptNumber);
    }

    /**
     * Returns true if the retry policy allows this attempt. This will always return true if the current attempt is not a retry
     * (i.e. it's the first request in the execution).
     */
    public boolean retryPolicyAllowsRetry() {
        if (isInitialAttempt()) {
            return true;
        }

        if (lastException instanceof NonRetryableException) {
            return false;
        }

        RetryPolicyContext context = retryPolicyContext(true);

        boolean willRetry = retryPolicy.aggregateRetryCondition().shouldRetry(context);
        if (!willRetry) {
            retryPolicy.aggregateRetryCondition().requestWillNotBeRetried(context);
        }

        return willRetry;
    }

    /**
     * Return the exception that should be thrown, because the retry policy did not allow the request to be retried.
     */
    public SdkException retryPolicyDisallowedRetryException() {
        context.executionContext().metricCollector().reportMetric(CoreMetric.RETRY_COUNT, retriesAttemptedSoFar(true));
        return lastException;
    }

    /**
     * Get the amount of time that the request should be delayed before being sent. This may be {@link Duration#ZERO}, such as
     * for the first request in the request series.
     */
    public Duration getBackoffDelay() {
        Duration result;
        if (isInitialAttempt()) {
            result = Duration.ZERO;
        } else {
            RetryPolicyContext context = retryPolicyContext(true);
            if (RetryUtils.isThrottlingException(lastException)) {
                result = retryPolicy.throttlingBackoffStrategy().computeDelayBeforeNextRetry(context);
            } else {
                result = retryPolicy.backoffStrategy().computeDelayBeforeNextRetry(context);
            }
        }
        context.executionAttributes().putAttribute(LAST_BACKOFF_DELAY_DURATION, result);
        return result;
    }

    /**
     * Log a message to the user at the debug level to indicate how long we will wait before retrying the request.
     */
    public void logBackingOff(Duration backoffDelay) {
        SdkStandardLogger.REQUEST_LOGGER.debug(() -> "Retryable error detected. Will retry in " +
                                                     backoffDelay.toMillis() + "ms. Request attempt number " +
                                                     attemptNumber, lastException);
    }

    /**
     * Retrieve the request to send to the service, including any detailed retry information headers.
     */
    public SdkHttpFullRequest requestToSend() {
        return request.toBuilder()
                      .putHeader(SDK_RETRY_INFO_HEADER, "attempt=" + attemptNumber + "; max=" + retryPolicy.numRetries())
                      .build();
    }

    /**
     * Log a message to the user at the debug level to indicate that we are sending the request to the service.
     */
    public void logSendingRequest() {
        SdkStandardLogger.REQUEST_LOGGER.debug(() -> (isInitialAttempt() ? "Sending" : "Retrying") + " Request: " + request);
    }

    /**
     * Adjust the client-side clock skew if the provided response indicates that there is a large skew between the client and
     * service. This will allow a retried request to be signed with what is likely to be a more accurate time.
     */
    public void adjustClockIfClockSkew(Response<?> response) {
        ClockSkewAdjuster clockSkewAdjuster = dependencies.clockSkewAdjuster();
        if (!response.isSuccess() && clockSkewAdjuster.shouldAdjust(response.exception())) {
            dependencies.updateTimeOffset(clockSkewAdjuster.getAdjustmentInSeconds(response.httpResponse()));
        }
    }

    /**
     * Notify the retry policy that the request attempt succeeded.
     */
    public void attemptSucceeded() {
        retryPolicy.aggregateRetryCondition().requestSucceeded(retryPolicyContext(false));
        context.executionContext().metricCollector().reportMetric(CoreMetric.RETRY_COUNT, retriesAttemptedSoFar(false));
    }

    /**
     * Retrieve the current attempt number, updated whenever {@link #startingAttempt()} is invoked.
     */
    public int getAttemptNumber() {
        return attemptNumber;
    }

    /**
     * Retrieve the last call failure exception encountered by this execution, updated whenever {@link #setLastException} is
     * invoked.
     */
    public SdkException getLastException() {
        return lastException;
    }

    /**
     * Update the {@link #getLastException()} value for this helper. This will be used to determine whether the request should
     * be retried.
     */
    public void setLastException(Throwable lastException) {
        if (lastException instanceof CompletionException) {
            setLastException(lastException.getCause());
        } else if (lastException instanceof SdkException) {
            this.lastException = (SdkException) lastException;
        } else {
            this.lastException = SdkClientException.create("Unable to execute HTTP request: " + lastException.getMessage(),
                                                           lastException);
        }
    }

    /**
     * Set the last HTTP response returned by the service. This will be used to determine whether the request should be retried.
     */
    public void setLastResponse(SdkHttpResponse lastResponse) {
        this.lastResponse = lastResponse;
    }

    /**
     * Whether rate limiting is enabled. Only {@link RetryMode#ADAPTIVE} enables rate limiting.
     */
    public boolean isRateLimitingEnabled() {
        return retryPolicy.retryMode() == RetryMode.ADAPTIVE;
    }

    /**
     * Whether rate limiting should fast fail.
     */
    public boolean isFastFailRateLimiting() {
        return Boolean.TRUE.equals(retryPolicy.isFastFailRateLimiting());
    }

    public boolean isLastExceptionThrottlingException() {
        if (lastException == null) {
            return false;
        }

        return RetryUtils.isThrottlingException(lastException);
    }

    /**
     * Acquire a send token from the rate limiter. Returns immediately if rate limiting is not enabled.
     */
    public void getSendToken() {
        if (!isRateLimitingEnabled()) {
            return;
        }

        boolean acquired = rateLimitingTokenBucket.acquire(1.0, isFastFailRateLimiting());

        if (!acquired) {
            String errorMessage = "Unable to acquire a send token immediately without waiting. This indicates that ADAPTIVE "
                                  + "retry mode is enabled, fast fail rate limiting is enabled, and that rate limiting is "
                                  + "engaged because of prior throttled requests. The request will not be executed.";
            throw SdkClientException.create(errorMessage);
        }
    }

    /**
     * Acquire a send token from the rate limiter in a non blocking manner. See
     * {@link RateLimitingTokenBucket#acquireNonBlocking(double, boolean)} for documentation on how to interpret the returned
     * value.
     */
    public OptionalDouble getSendTokenNonBlocking() {
        if (!isRateLimitingEnabled()) {
            return OptionalDouble.of(0.0);
        }

        return rateLimitingTokenBucket.acquireNonBlocking(1.0, isFastFailRateLimiting());
    }

    /**
     * Conditionally updates the sending rate of the rate limiter when an error response is received. This operation is a noop
     * if rate limiting is not enabled.
     */
    public void updateClientSendingRateForErrorResponse() {
        if (!isRateLimitingEnabled()) {
            return;
        }
        // Only throttling errors affect the sending rate. For non error
        // responses, they're handled by
        // updateClientSendingRateForSuccessResponse()
        if (isLastExceptionThrottlingException()) {
            rateLimitingTokenBucket.updateClientSendingRate(true);
        }
    }

    /**
     * Conditionally updates the sending rate of the rate limiter when an error response is received. This operation is a noop
     * if rate limiting is not enabled.
     */
    public void updateClientSendingRateForSuccessResponse() {
        if (!isRateLimitingEnabled()) {
            return;
        }
        rateLimitingTokenBucket.updateClientSendingRate(false);
    }

    private boolean isInitialAttempt() {
        return attemptNumber == 1;
    }

    private RetryPolicyContext retryPolicyContext(boolean isBeforeAttemptSent) {
        return RetryPolicyContext.builder()
                                 .request(request)
                                 .originalRequest(context.originalRequest())
                                 .exception(lastException)
                                 .retriesAttempted(retriesAttemptedSoFar(isBeforeAttemptSent))
                                 .executionAttributes(context.executionAttributes())
                                 .httpStatusCode(lastResponse == null ? null : lastResponse.statusCode())
                                 .build();
    }

    /**
     * Retrieve the number of retries sent so far in the request execution. This depends on whether or not we've actually
     * sent the request yet during this attempt.
     *
     * Assuming we're executing attempt 3, the number of retries attempted varies based on whether the request has been sent to
     * the service yet. Before we send the request, the number of retries is 1 (from attempt 2). After we send the request, the
     * number of retries is 2 (from attempt 2 and attempt 3).
     */
    private int retriesAttemptedSoFar(boolean isBeforeAttemptSent) {
        return Math.max(0, isBeforeAttemptSent ? attemptNumber - 2 : attemptNumber - 1);
    }
}
