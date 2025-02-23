/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.nio.spi.s3;


import static java.util.concurrent.TimeUnit.MINUTES;
import static software.amazon.nio.spi.s3.util.TimeOutUtils.TIMEOUT_TIME_LENGTH_1;
import static software.amazon.nio.spi.s3.util.TimeOutUtils.logAndGenerateExceptionOnTimeOut;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.core.exception.RetryableException;
import software.amazon.awssdk.core.retry.backoff.EqualJitterBackoffStrategy;
import software.amazon.awssdk.core.retry.conditions.OrRetryCondition;
import software.amazon.awssdk.core.retry.conditions.RetryCondition;
import software.amazon.awssdk.core.retry.conditions.RetryOnClockSkewCondition;
import software.amazon.awssdk.core.retry.conditions.RetryOnExceptionsCondition;
import software.amazon.awssdk.core.retry.conditions.RetryOnStatusCodeCondition;
import software.amazon.awssdk.core.retry.conditions.RetryOnThrottlingCondition;
import software.amazon.awssdk.http.HttpStatusCode;
import software.amazon.awssdk.http.SdkHttpResponse;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3AsyncClientBuilder;
import software.amazon.awssdk.services.s3.S3CrtAsyncClientBuilder;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.nio.spi.s3.config.S3NioSpiConfiguration;

/**
 * Factory/builder class that creates sync and async S3 clients. It also provides
 * default clients that can be used for basic operations (e.g. bucket discovery).
 */
public class S3ClientProvider {

    private static final Logger logger = LoggerFactory.getLogger(S3ClientProvider.class);

    /**
     * Default asynchronous client using the "<a href="https://s3.us-east-1.amazonaws.com">...</a>" endpoint
     */
    private static final S3AsyncClient DEFAULT_CLIENT = S3AsyncClient.builder()
        .endpointOverride(URI.create("https://s3.us-east-1.amazonaws.com"))
        .crossRegionAccessEnabled(true)
        .region(Region.US_EAST_1)
        .build();

    /**
     * Configuration
     */
    protected final S3NioSpiConfiguration configuration;

    /**
     * Default S3CrtAsyncClientBuilder
     */
    protected S3CrtAsyncClientBuilder asyncClientBuilder =
            S3AsyncClient.crtBuilder()
                    .crossRegionAccessEnabled(true);

    final RetryCondition retryCondition;

    private final EqualJitterBackoffStrategy backoffStrategy = EqualJitterBackoffStrategy.builder()
        .baseDelay(Duration.ofMillis(200L))
        .maxBackoffTime(Duration.ofSeconds(5L))
        .build();

    {
        final var retryableStatusCodes = Set.of(
            HttpStatusCode.INTERNAL_SERVER_ERROR,
            HttpStatusCode.BAD_GATEWAY,
            HttpStatusCode.SERVICE_UNAVAILABLE,
            HttpStatusCode.GATEWAY_TIMEOUT
        );

        final var retryableExceptions = Set.of(
            RetryableException.class,
            IOException.class,
            ApiCallAttemptTimeoutException.class,
            ApiCallTimeoutException.class);

        retryCondition = OrRetryCondition.create(
            RetryOnStatusCodeCondition.create(retryableStatusCodes),
            RetryOnExceptionsCondition.create(retryableExceptions),
            RetryOnClockSkewCondition.create(),
            RetryOnThrottlingCondition.create()
        );
    }

    public S3ClientProvider(S3NioSpiConfiguration c) {
        this.configuration = (c == null) ? new S3NioSpiConfiguration() : c;
    }

    public void asyncClientBuilder(final S3CrtAsyncClientBuilder builder) {
        asyncClientBuilder = builder;
    }


    /**
     * This method returns a universal client (i.e. not bound to any region)
     * that can be used by certain S3 operations for discovery.
     * This is the same as universalClient(false);
     *
     * @return a S3Client not bound to a region
     */
    S3AsyncClient universalClient() {
        return DEFAULT_CLIENT;
    }


    /**
     * Generates a sync client for the named bucket using the provided location
     * discovery client.
     *
     * @param bucket the named of the bucket to make the client for
     * @param crt    whether to return a CRT async client or not
     * @return an S3 client appropriate for the region of the named bucket
     */
    protected S3AsyncClient generateClient(String bucket, boolean crt) {
        try {
            return generateClient(bucket, universalClient(), crt);
        } catch (ExecutionException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }


    /**
     * Generate an async  client for the named bucket using a provided client to
     * determine the location of the named client
     *
     * @param bucketName     the name of the bucket to make the client for
     * @param locationClient the client used to determine the location of the
     *                       named bucket, recommend using DEFAULT_CLIENT
     * @param crt            whether to return a CRT async client or not
     * @return an S3 client appropriate for the region of the named bucket
     */
    S3AsyncClient generateClient(String bucketName, S3AsyncClient locationClient, boolean crt)
            throws ExecutionException, InterruptedException {
        return getClientForBucket(bucketName, locationClient, (region) -> asyncClientForRegion(region, crt));
    }

    private S3AsyncClient getClientForBucket(
        String bucketName,
        S3AsyncClient locationClient,
        Function<String, S3AsyncClient> getClientForRegion
    ) throws ExecutionException, InterruptedException {
        logger.debug("generating client for bucket: '{}'", bucketName);
        S3AsyncClient bucketSpecificClient = null;

        if (configuration.endpointUri() == null) {
            // we try to locate a bucket only if no endpoint is provided, which means we are dealing with AWS S3 buckets
            var bucketLocation = determineBucketLocation(bucketName, locationClient);

            if (bucketLocation != null) {
                bucketSpecificClient = getClientForRegion.apply(bucketLocation);
            } else {
                // if here, no S3 nor other client has been created yet, and we do not
                // have a location; we'll let it figure out from the profile region
                logger.warn("Unable to determine the region of bucket: '{}'. Generating a client for the profile region.",
                    bucketName);
            }
        }

        return (bucketSpecificClient != null)
            ? bucketSpecificClient
            : getClientForRegion.apply(configuration.getRegion());
    }

    private String determineBucketLocation(String bucketName, S3AsyncClient locationClient)
            throws ExecutionException, InterruptedException {
        try {
            return getBucketLocation(bucketName, locationClient);
        } catch (S3Exception e) {
            if (isForbidden(e)) {
                logger.debug("Cannot determine location of '{}' bucket directly", bucketName);
                return getBucketLocationFromHead(bucketName, locationClient);
            } else {
                throw e;
            }
        }
    }

    private String getBucketLocation(String bucketName, S3AsyncClient locationClient)
            throws ExecutionException, InterruptedException {
        logger.debug("determining bucket location with getBucketLocation");
        try {
            return locationClient.getBucketLocation(builder -> builder.bucket(bucketName))
                    .get(TIMEOUT_TIME_LENGTH_1, MINUTES).locationConstraintAsString();
        } catch (TimeoutException e) {
            throw logAndGenerateExceptionOnTimeOut(
                    logger,
                    "generateClient",
                    TIMEOUT_TIME_LENGTH_1,
                    MINUTES);
        }
    }

    private String getBucketLocationFromHead(String bucketName, S3AsyncClient locationClient)
            throws ExecutionException, InterruptedException {
        try {
            logger.debug("Attempting to obtain bucket '{}' location with headBucket operation", bucketName);
            final var headBucketResponse = locationClient.headBucket(builder -> builder.bucket(bucketName));
            return getBucketRegionFromResponse(headBucketResponse.get(TIMEOUT_TIME_LENGTH_1, MINUTES).sdkHttpResponse());
        } catch (S3Exception e) {
            if (isRedirect(e)) {
                return getBucketRegionFromResponse(e.awsErrorDetails().sdkHttpResponse());
            } else {
                throw e;
            }
        } catch (TimeoutException e) {
            throw logAndGenerateExceptionOnTimeOut(
                    logger,
                    "generateClient",
                    TIMEOUT_TIME_LENGTH_1,
                    MINUTES);
        }
    }

    private boolean isForbidden(S3Exception e) {
        return e.statusCode() == 403;
    }

    private boolean isRedirect(S3Exception e) {
        return e.statusCode() == 301;
    }

    private String getBucketRegionFromResponse(SdkHttpResponse response) {
        return response.firstMatchingHeader("x-amz-bucket-region").orElseThrow(() ->
            new NoSuchElementException("Head Bucket Response doesn't include the header 'x-amz-bucket-region'")
        );
    }

    private S3AsyncClient asyncClientForRegion(String regionName, boolean crt) {
        if (!crt) {
            return configureClientForRegion(regionName, S3AsyncClient.builder());
        }
        return configureCrtClientForRegion(regionName);
    }

    private S3AsyncClient configureClientForRegion(
        String regionName,
        S3AsyncClientBuilder builder
    ) {
        var region = getRegionFromRegionName(regionName);
        logger.debug("bucket region is: '{}'", region.id());

        builder
            .forcePathStyle(configuration.getForcePathStyle())
            .region(region)
            .overrideConfiguration(
                conf -> conf.retryPolicy(
                    configBuilder -> configBuilder.retryCondition(retryCondition).backoffStrategy(backoffStrategy)
                )
            );

        var endpointUri = configuration.endpointUri();
        if (endpointUri != null) {
            builder.endpointOverride(endpointUri);
        }

        var credentials = configuration.getCredentials();
        if (credentials != null) {
            builder.credentialsProvider(() -> credentials);
        }

        return builder.build();
    }

    private S3AsyncClient configureCrtClientForRegion(String regionName) {
        var region = getRegionFromRegionName(regionName);
        logger.debug("bucket region is: '{}'", region.id());

        var endpointUri = configuration.endpointUri();
        if (endpointUri != null) {
            asyncClientBuilder.endpointOverride(endpointUri);
        }

        var credentials = configuration.getCredentials();
        if (credentials != null) {
            asyncClientBuilder.credentialsProvider(() -> credentials);
        }

        return asyncClientBuilder.forcePathStyle(configuration.getForcePathStyle())
                .region(region)
                .build();
    }

    private static Region getRegionFromRegionName(String regionName) {
        return (regionName == null || regionName.isBlank()) ? Region.US_EAST_1 : Region.of(regionName);
    }

}
