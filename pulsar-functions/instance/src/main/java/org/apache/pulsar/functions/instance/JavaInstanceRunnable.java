/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.pulsar.functions.instance;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import io.netty.buffer.ByteBuf;
import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.Summary;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.jodah.typetools.TypeResolver;
import org.apache.bookkeeper.api.StorageClient;
import org.apache.bookkeeper.api.kv.Table;
import org.apache.bookkeeper.clients.StorageClientBuilder;
import org.apache.bookkeeper.clients.admin.StorageAdminClient;
import org.apache.bookkeeper.clients.config.StorageClientSettings;
import org.apache.bookkeeper.clients.exceptions.NamespaceNotFoundException;
import org.apache.bookkeeper.clients.exceptions.StreamNotFoundException;
import org.apache.bookkeeper.stream.proto.NamespaceConfiguration;
import org.apache.bookkeeper.stream.proto.StorageType;
import org.apache.bookkeeper.stream.proto.StreamConfiguration;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.ThreadContext;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.SubscriptionType;
import org.apache.pulsar.client.impl.PulsarClientImpl;
import org.apache.pulsar.common.functions.ConsumerConfig;
import org.apache.pulsar.common.functions.FunctionConfig;
import org.apache.pulsar.functions.api.Function;
import org.apache.pulsar.functions.api.Record;
import org.apache.pulsar.functions.instance.state.StateContextImpl;
import org.apache.pulsar.functions.proto.Function.SinkSpec;
import org.apache.pulsar.functions.proto.Function.SourceSpec;
import org.apache.pulsar.functions.proto.InstanceCommunication;
import org.apache.pulsar.functions.proto.InstanceCommunication.MetricsData.Builder;
import org.apache.pulsar.functions.secretsprovider.SecretsProvider;
import org.apache.pulsar.functions.sink.PulsarSink;
import org.apache.pulsar.functions.sink.PulsarSinkConfig;
import org.apache.pulsar.functions.sink.PulsarSinkDisable;
import org.apache.pulsar.functions.source.PulsarSource;
import org.apache.pulsar.functions.source.PulsarSourceConfig;
import org.apache.pulsar.functions.utils.FunctionDetailsUtils;
import org.apache.pulsar.functions.utils.Reflections;
import org.apache.pulsar.functions.utils.StateUtils;
import org.apache.pulsar.functions.utils.functioncache.FunctionCacheManager;
import org.apache.pulsar.io.core.Sink;
import org.apache.pulsar.io.core.Source;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileNotFoundException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.apache.bookkeeper.common.concurrent.FutureUtils.result;
import static org.apache.bookkeeper.stream.protocol.ProtocolConstants.DEFAULT_STREAM_CONF;

/**
 * A function container implemented using java thread.
 */
@Slf4j
public class JavaInstanceRunnable implements AutoCloseable, Runnable {

    // The class loader that used for loading functions
    private ClassLoader fnClassLoader;
    private final InstanceConfig instanceConfig;
    private final FunctionCacheManager fnCache;
    private final String jarFile;

    // input topic consumer & output topic producer
    private final PulsarClientImpl client;

    private LogAppender logAppender;

    // provide tables for storing states
    private final String stateStorageServiceUrl;
    @Getter(AccessLevel.PACKAGE)
    private StorageClient storageClient;
    @Getter(AccessLevel.PACKAGE)
    private Table<ByteBuf, ByteBuf> stateTable;

    private JavaInstance javaInstance;
    @Getter
    private Throwable deathException;

    // function stats
    private final FunctionStats stats;

    private Record<?> currentRecord;

    private Source source;
    private Sink sink;

    private final SecretsProvider secretsProvider;

    private CollectorRegistry collectorRegistry;
    private final String[] metricsLabels;

    public JavaInstanceRunnable(InstanceConfig instanceConfig,
                                FunctionCacheManager fnCache,
                                String jarFile,
                                PulsarClient pulsarClient,
                                String stateStorageServiceUrl,
                                SecretsProvider secretsProvider,
                                CollectorRegistry collectorRegistry) {
        this.instanceConfig = instanceConfig;
        this.fnCache = fnCache;
        this.jarFile = jarFile;
        this.client = (PulsarClientImpl) pulsarClient;
        this.stateStorageServiceUrl = stateStorageServiceUrl;
        this.stats = new FunctionStats(collectorRegistry);
        this.secretsProvider = secretsProvider;
        this.collectorRegistry = collectorRegistry;
        this.metricsLabels = new String[]{
                instanceConfig.getFunctionDetails().getTenant(),
                String.format("%s/%s", instanceConfig.getFunctionDetails().getTenant(),
                        instanceConfig.getFunctionDetails().getNamespace()),
                String.format("%s/%s/%s", instanceConfig.getFunctionDetails().getTenant(),
                        instanceConfig.getFunctionDetails().getNamespace(),
                        instanceConfig.getFunctionDetails().getName()),
                String.valueOf(instanceConfig.getInstanceId()),
                instanceConfig.getClusterName()
        };
    }

    /**
     * NOTE: this method should be called in the instance thread, in order to make class loading work.
     */
    JavaInstance setupJavaInstance(ContextImpl contextImpl) throws Exception {
        // initialize the thread context
        ThreadContext.put("function", FunctionDetailsUtils.getFullyQualifiedName(instanceConfig.getFunctionDetails()));
        ThreadContext.put("functionname", instanceConfig.getFunctionDetails().getName());
        ThreadContext.put("instance", instanceConfig.getInstanceName());

        log.info("Starting Java Instance {} : \n Details = {}",
            instanceConfig.getFunctionDetails().getName(), instanceConfig.getFunctionDetails());

        // start the function thread
        loadJars();

        ClassLoader clsLoader = Thread.currentThread().getContextClassLoader();
        Object object = Reflections.createInstance(
                instanceConfig.getFunctionDetails().getClassName(),
                clsLoader);
        if (!(object instanceof Function) && !(object instanceof java.util.function.Function)) {
            throw new RuntimeException("User class must either be Function or java.util.Function");
        }

        // start the state table
        setupStateTable();
        // start the output producer
        setupOutput(contextImpl);
        // start the input consumer
        setupInput(contextImpl);
        // start any log topic handler
        setupLogHandler();

        return new JavaInstance(contextImpl, object);
    }

    ContextImpl setupContext() {
        List<String> inputTopics = null;
        if (source instanceof PulsarSource) {
            inputTopics = ((PulsarSource<?>) source).getInputTopics();
        }
        Logger instanceLog = LoggerFactory.getLogger(
                "function-" + instanceConfig.getFunctionDetails().getName());
        return new ContextImpl(instanceConfig, instanceLog, client, inputTopics, secretsProvider, collectorRegistry, metricsLabels);
    }

    /**
     * The core logic that initialize the instance thread and executes the function
     */
    @Override
    public void run() {
        String functionName = null;
        try {
            ContextImpl contextImpl = setupContext();
            functionName = String.format("%s-%s", contextImpl.getTenant(), contextImpl.getFunctionName());
            javaInstance = setupJavaInstance(contextImpl);
            if (null != stateTable) {
                StateContextImpl stateContext = new StateContextImpl(stateTable);
                javaInstance.getContext().setStateContext(stateContext);
            }
            while (true) {
                currentRecord = readInput();

                // increment number of records received from source
                stats.statTotalRecordsRecieved.labels(metricsLabels).inc();

                if (instanceConfig.getFunctionDetails().getProcessingGuarantees() == org.apache.pulsar.functions
                        .proto.Function.ProcessingGuarantees.ATMOST_ONCE) {
                    if (instanceConfig.getFunctionDetails().getAutoAck()) {
                        currentRecord.ack();
                    }
                }

                addLogTopicHandler();
                JavaExecutionResult result;

                // set last invocation time
                stats.statlastInvocation.labels(metricsLabels).set(System.currentTimeMillis());

                // start time for process latency stat
                Summary.Timer requestTimer = stats.statProcessLatency.labels(metricsLabels).startTimer();

                // process the message
                result = javaInstance.handleMessage(currentRecord, currentRecord.getValue());

                // register end time
                requestTimer.observeDuration();
                // increment total processed
                stats.statTotalProcessed.labels(metricsLabels).inc();

                removeLogTopicHandler();

                if (log.isDebugEnabled()) {
                    log.debug("Got result: {}", result.getResult());
                }

                try {
                    processResult(currentRecord, result);
                } catch (Exception e) {
                    log.warn("Failed to process result of message {}", currentRecord, e);
                    currentRecord.fail();
                }
            }
        } catch (Throwable t) {
            log.error("[{}] Uncaught exception in Java Instance", functionName, t);
            deathException = t;
            stats.statTotalSysExceptions.labels(metricsLabels).inc();
            stats.addSystemException(t);
            return;
        } finally {
            log.info("Closing instance");
            close();
        }
    }

    private void loadJars() throws Exception {
        try {
            // Let's first try to treat it as a nar archive
            fnCache.registerFunctionInstanceWithArchive(
                instanceConfig.getFunctionId(),
                instanceConfig.getInstanceName(),
                jarFile);
        } catch (FileNotFoundException e) {
            // create the function class loader
            fnCache.registerFunctionInstance(
                    instanceConfig.getFunctionId(),
                    instanceConfig.getInstanceName(),
                    Arrays.asList(jarFile),
                    Collections.emptyList());
        }

        log.info("Initialize function class loader for function {} at function cache manager",
                instanceConfig.getFunctionDetails().getName());

        this.fnClassLoader = fnCache.getClassLoader(instanceConfig.getFunctionId());
        if (null == fnClassLoader) {
            throw new Exception("No function class loader available.");
        }

        // make sure the function class loader is accessible thread-locally
        Thread.currentThread().setContextClassLoader(fnClassLoader);
    }

    private void setupStateTable() throws Exception {
        if (null == stateStorageServiceUrl) {
            return;
        }

        String tableNs = StateUtils.getStateNamespace(
            instanceConfig.getFunctionDetails().getTenant(),
            instanceConfig.getFunctionDetails().getNamespace()
        );
        String tableName = instanceConfig.getFunctionDetails().getName();

        StorageClientSettings settings = StorageClientSettings.newBuilder()
                .serviceUri(stateStorageServiceUrl)
                .clientName("function-" + tableNs + "/" + tableName)
                .build();

        // we defer creation of the state table until a java instance is running here.
        try (StorageAdminClient storageAdminClient = StorageClientBuilder.newBuilder()
                .withSettings(settings)
                .buildAdmin()) {
            StreamConfiguration streamConf = StreamConfiguration.newBuilder(DEFAULT_STREAM_CONF)
                .setInitialNumRanges(4)
                .setMinNumRanges(4)
                .setStorageType(StorageType.TABLE)
                .build();
            try {
                result(storageAdminClient.getStream(tableNs, tableName));
            } catch (NamespaceNotFoundException nnfe) {
                result(storageAdminClient.createNamespace(tableNs, NamespaceConfiguration.newBuilder()
                        .setDefaultStreamConf(streamConf)
                        .build()));
                result(storageAdminClient.createStream(tableNs, tableName, streamConf));
            } catch (StreamNotFoundException snfe) {
                result(storageAdminClient.createStream(tableNs, tableName, streamConf));
            }
        }

        log.info("Starting state table for function {}", instanceConfig.getFunctionDetails().getName());
        this.storageClient = StorageClientBuilder.newBuilder()
                .withSettings(settings)
                .withNamespace(tableNs)
                .build();
        this.stateTable = result(storageClient.openTable(tableName));
    }

    private void processResult(Record srcRecord,
                               JavaExecutionResult result) throws Exception {
        if (result.getUserException() != null) {
            log.info("Encountered user exception when processing message {}", srcRecord, result.getUserException());
            stats.statTotalUserExceptions.labels(metricsLabels).inc();
            stats.addUserException(result.getUserException() );
            srcRecord.fail();
        } else {
            if (result.getResult() != null) {
                sendOutputMessage(srcRecord, result.getResult());
            } else {
                if (instanceConfig.getFunctionDetails().getAutoAck()) {
                    // the function doesn't produce any result or the user doesn't want the result.
                    srcRecord.ack();
                }
            }
            // increment total successfully processed
            stats.statTotalProcessedSuccessfully.labels(metricsLabels).inc();
        }
    }

    private void sendOutputMessage(Record srcRecord, Object output) {
        try {
            this.sink.write(new SinkRecord<>(srcRecord, output));
        } catch (Exception e) {
            log.info("Encountered exception in sink write: ", e);
            throw new RuntimeException(e);
        }
    }

    private Record readInput() {
        Record record;
        try {
            record = this.source.read();
        } catch (Exception e) {
            log.info("Encountered exception in source read: ", e);
            throw new RuntimeException(e);
        }

        // check record is valid
        if (record == null) {
            throw new IllegalArgumentException("The record returned by the source cannot be null");
        } else if (record.getValue() == null) {
            throw new IllegalArgumentException("The value in the record returned by the source cannot be null");
        }
        return record;
    }

    @Override
    public void close() {
        if (source != null) {
            try {
                source.close();
            } catch (Exception e) {
                log.error("Failed to close source {}", instanceConfig.getFunctionDetails().getSource().getClassName(), e);

            }
        }

        if (sink != null) {
            try {
                sink.close();
            } catch (Exception e) {
                log.error("Failed to close sink {}", instanceConfig.getFunctionDetails().getSource().getClassName(), e);
            }
        }

        if (null != javaInstance) {
            javaInstance.close();
        }

        // kill the state table
        if (null != stateTable) {
            stateTable.close();
            stateTable = null;
        }
        if (null != storageClient) {
            storageClient.close();
        }

        // once the thread quits, clean up the instance
        fnCache.unregisterFunctionInstance(
                instanceConfig.getFunctionId(),
                instanceConfig.getInstanceName());
        log.info("Unloading JAR files for function {}", instanceConfig);
    }

    public InstanceCommunication.MetricsData getAndResetMetrics() {
        InstanceCommunication.MetricsData.Builder bldr = createMetricsDataBuilder();
        stats.reset();
        if (javaInstance != null) {
            InstanceCommunication.MetricsData userMetrics =  javaInstance.getAndResetMetrics();
            if (userMetrics != null) {
                bldr.putAllMetrics(userMetrics.getMetricsMap());
            }
        }
        return bldr.build();
    }

    public InstanceCommunication.MetricsData getMetrics() {
        InstanceCommunication.MetricsData.Builder bldr = createMetricsDataBuilder();
        if (javaInstance != null) {
            InstanceCommunication.MetricsData userMetrics =  javaInstance.getMetrics();
            if (userMetrics != null) {
                bldr.putAllMetrics(userMetrics.getMetricsMap());
            }
        }
        return bldr.build();
    }

    public void resetMetrics() {
        stats.reset();
        javaInstance.resetMetrics();
    }

    private Builder createMetricsDataBuilder() {
        InstanceCommunication.MetricsData.Builder bldr = InstanceCommunication.MetricsData.newBuilder();
        addSystemMetrics(FunctionStats.PULSAR_FUNCTION_PROCESSED_TOTAL, stats.statTotalProcessed.labels(metricsLabels).get(), bldr);
        addSystemMetrics(FunctionStats.PULSAR_FUNCTION_PROCESSED_SUCCESSFULLY_TOTAL, stats.statTotalProcessedSuccessfully.labels(metricsLabels).get(), bldr);
        addSystemMetrics(FunctionStats.PULSAR_FUNCTION_SYSTEM_EXCEPTIONS_TOTAL,  stats.statTotalSysExceptions.labels(metricsLabels).get(), bldr);
        addSystemMetrics(FunctionStats.PULSAR_FUNCTION_USER_EXCEPTIONS_TOTAL, stats.statTotalUserExceptions.labels(metricsLabels).get(), bldr);
        addSystemMetrics(FunctionStats.PULSAR_FUNCTION_RECEIVED_TOTAL, stats.statTotalRecordsRecieved.labels(metricsLabels).get(), bldr);
        addSystemMetrics(FunctionStats.PULSAR_FUNCTION_PROCESS_LATENCY_MS,
                stats.statProcessLatency.labels(metricsLabels).get().count <= 0.0
                        ? 0 : stats.statProcessLatency.labels(metricsLabels).get().sum / stats.statProcessLatency.labels(metricsLabels).get().count,
                bldr);
        addSystemMetrics(FunctionStats.PULSAR_FUNCTION_LAST_INVOCATION, stats.statlastInvocation.labels(metricsLabels).get(), bldr);
        return bldr;
    }

    public InstanceCommunication.FunctionStatus.Builder getFunctionStatus() {
        InstanceCommunication.FunctionStatus.Builder functionStatusBuilder = InstanceCommunication.FunctionStatus.newBuilder();
        functionStatusBuilder.setNumProcessed((long) stats.statTotalProcessed.labels(metricsLabels).get());
        functionStatusBuilder.setNumSuccessfullyProcessed((long) stats.statTotalProcessedSuccessfully.labels(metricsLabels).get());
        functionStatusBuilder.setNumUserExceptions((long) stats.statTotalUserExceptions.labels(metricsLabels).get());
        stats.getLatestUserExceptions().forEach(ex -> {
            functionStatusBuilder.addLatestUserExceptions(ex);
        });
        functionStatusBuilder.setNumSystemExceptions((long) stats.statTotalSysExceptions.labels(metricsLabels).get());
        stats.getLatestSystemExceptions().forEach(ex -> {
            functionStatusBuilder.addLatestSystemExceptions(ex);
        });
        functionStatusBuilder.setAverageLatency(
                stats.statProcessLatency.labels(metricsLabels).get().count == 0.0
                        ? 0 : stats.statProcessLatency.labels(metricsLabels).get().sum / stats.statProcessLatency
                        .labels(metricsLabels).get().count);
        functionStatusBuilder.setLastInvocationTime((long) stats.statlastInvocation.labels(metricsLabels).get());
        return functionStatusBuilder;
    }

    private static void addSystemMetrics(String metricName, double value, InstanceCommunication.MetricsData.Builder bldr) {
        InstanceCommunication.MetricsData.DataDigest digest =
                InstanceCommunication.MetricsData.DataDigest.newBuilder()
                        .setCount(value).setSum(value).setMax(value).setMin(0).build();
        bldr.putMetrics(metricName, digest);
    }

    private void setupLogHandler() {
        if (instanceConfig.getFunctionDetails().getLogTopic() != null &&
                !instanceConfig.getFunctionDetails().getLogTopic().isEmpty()) {
            logAppender = new LogAppender(client, instanceConfig.getFunctionDetails().getLogTopic(),
                    FunctionDetailsUtils.getFullyQualifiedName(instanceConfig.getFunctionDetails()));
            logAppender.start();
        }
    }

    private void addLogTopicHandler() {
        if (logAppender == null) return;
        LoggerContext context = LoggerContext.getContext(false);
        Configuration config = context.getConfiguration();
        config.addAppender(logAppender);
        for (final LoggerConfig loggerConfig : config.getLoggers().values()) {
            loggerConfig.addAppender(logAppender, null, null);
        }
        config.getRootLogger().addAppender(logAppender, null, null);
    }

    private void removeLogTopicHandler() {
        if (logAppender == null) return;
        LoggerContext context = LoggerContext.getContext(false);
        Configuration config = context.getConfiguration();
        for (final LoggerConfig loggerConfig : config.getLoggers().values()) {
            loggerConfig.removeAppender(logAppender.getName());
        }
        config.getRootLogger().removeAppender(logAppender.getName());
    }

    public void setupInput(ContextImpl contextImpl) throws Exception {

        SourceSpec sourceSpec = this.instanceConfig.getFunctionDetails().getSource();
        Object object;
        // If source classname is not set, we default pulsar source
        if (sourceSpec.getClassName().isEmpty()) {
            PulsarSourceConfig pulsarSourceConfig = new PulsarSourceConfig();
            sourceSpec.getInputSpecsMap().forEach((topic, conf) -> {
                ConsumerConfig consumerConfig = ConsumerConfig.builder().isRegexPattern(conf.getIsRegexPattern()).build();
                if (conf.getSchemaType() != null && !conf.getSchemaType().isEmpty()) {
                    consumerConfig.setSchemaType(conf.getSchemaType());
                } else if (conf.getSerdeClassName() != null && !conf.getSerdeClassName().isEmpty()) {
                    consumerConfig.setSerdeClassName(conf.getSerdeClassName());
                }
                pulsarSourceConfig.getTopicSchema().put(topic, consumerConfig);
            });

            sourceSpec.getTopicsToSerDeClassNameMap().forEach((topic, serde) -> {
                pulsarSourceConfig.getTopicSchema().put(topic,
                        ConsumerConfig.builder()
                                .serdeClassName(serde)
                                .isRegexPattern(false)
                                .build());
            });

            if (!StringUtils.isEmpty(sourceSpec.getTopicsPattern())) {
                pulsarSourceConfig.getTopicSchema().get(sourceSpec.getTopicsPattern()).setRegexPattern(true);
            }

            pulsarSourceConfig.setSubscriptionName(
                    StringUtils.isNotBlank(sourceSpec.getSubscriptionName()) ? sourceSpec.getSubscriptionName()
                            : FunctionDetailsUtils.getFullyQualifiedName(this.instanceConfig.getFunctionDetails()));
            pulsarSourceConfig.setProcessingGuarantees(
                    FunctionConfig.ProcessingGuarantees.valueOf(
                            this.instanceConfig.getFunctionDetails().getProcessingGuarantees().name()));

            switch (sourceSpec.getSubscriptionType()) {
                case FAILOVER:
                    pulsarSourceConfig.setSubscriptionType(SubscriptionType.Failover);
                    break;
                default:
                    pulsarSourceConfig.setSubscriptionType(SubscriptionType.Shared);
                    break;
            }

            pulsarSourceConfig.setTypeClassName(sourceSpec.getTypeClassName());

            if (sourceSpec.getTimeoutMs() > 0 ) {
                pulsarSourceConfig.setTimeoutMs(sourceSpec.getTimeoutMs());
            }

            if (this.instanceConfig.getFunctionDetails().hasRetryDetails()) {
                pulsarSourceConfig.setMaxMessageRetries(this.instanceConfig.getFunctionDetails().getRetryDetails().getMaxMessageRetries());
                pulsarSourceConfig.setDeadLetterTopic(this.instanceConfig.getFunctionDetails().getRetryDetails().getDeadLetterTopic());
            }
            object = new PulsarSource(this.client, pulsarSourceConfig,
                    FunctionDetailsUtils.getFullyQualifiedName(this.instanceConfig.getFunctionDetails()));
        } else {
            object = Reflections.createInstance(
                    sourceSpec.getClassName(),
                    Thread.currentThread().getContextClassLoader());
        }

        Class<?>[] typeArgs;
        if (object instanceof Source) {
            typeArgs = TypeResolver.resolveRawArguments(Source.class, object.getClass());
            assert typeArgs.length > 0;
        } else {
            throw new RuntimeException("Source does not implement correct interface");
        }
        this.source = (Source<?>) object;

        if (sourceSpec.getConfigs().isEmpty()) {
            this.source.open(new HashMap<>(), contextImpl);
        } else {
            this.source.open(new Gson().fromJson(sourceSpec.getConfigs(),
                    new TypeToken<Map<String, Object>>(){}.getType()), contextImpl);
        }
    }

    public void setupOutput(ContextImpl contextImpl) throws Exception {

        SinkSpec sinkSpec = this.instanceConfig.getFunctionDetails().getSink();
        Object object;
        // If sink classname is not set, we default pulsar sink
        if (sinkSpec.getClassName().isEmpty()) {
            if (StringUtils.isEmpty(sinkSpec.getTopic())) {
                object = PulsarSinkDisable.INSTANCE;
            } else {
                PulsarSinkConfig pulsarSinkConfig = new PulsarSinkConfig();
                pulsarSinkConfig.setProcessingGuarantees(FunctionConfig.ProcessingGuarantees.valueOf(
                        this.instanceConfig.getFunctionDetails().getProcessingGuarantees().name()));
                pulsarSinkConfig.setTopic(sinkSpec.getTopic());

                if (!StringUtils.isEmpty(sinkSpec.getSchemaType())) {
                    pulsarSinkConfig.setSchemaType(sinkSpec.getSchemaType());
                } else if (!StringUtils.isEmpty(sinkSpec.getSerDeClassName())) {
                    pulsarSinkConfig.setSerdeClassName(sinkSpec.getSerDeClassName());
                }

                pulsarSinkConfig.setTypeClassName(sinkSpec.getTypeClassName());

                object = new PulsarSink(this.client, pulsarSinkConfig,
                        FunctionDetailsUtils.getFullyQualifiedName(this.instanceConfig.getFunctionDetails()));
            }
        } else {
            object = Reflections.createInstance(
                    sinkSpec.getClassName(),
                    Thread.currentThread().getContextClassLoader());
        }

        if (object instanceof Sink) {
            this.sink = (Sink) object;
        } else {
            throw new RuntimeException("Sink does not implement correct interface");
        }
        if (sinkSpec.getConfigs().isEmpty()) {
            this.sink.open(new HashMap<>(), contextImpl);
        } else {
            this.sink.open(new Gson().fromJson(sinkSpec.getConfigs(),
                    new TypeToken<Map<String, Object>>() {}.getType()), contextImpl);
        }
    }
}
