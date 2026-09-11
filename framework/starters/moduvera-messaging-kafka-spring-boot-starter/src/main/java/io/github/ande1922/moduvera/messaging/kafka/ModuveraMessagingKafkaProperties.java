package io.github.ande1922.moduvera.messaging.kafka;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("moduvera.messaging.kafka")
public class ModuveraMessagingKafkaProperties {

    private boolean relayEnabled = true;
    private final String relayInstanceId = UUID.randomUUID().toString();
    private int relayBatchSize = 10;
    private Duration relayPollInterval = Duration.ofSeconds(1);
    private Duration claimLease = Duration.ofSeconds(30);
    private Duration leaseSafetyMargin = Duration.ofSeconds(2);
    private Duration brokerAckTimeout = Duration.ofSeconds(2);
    private Duration databaseStateUpdateBudget = Duration.ofSeconds(1);
    private Duration relayRunBudget = Duration.ofSeconds(10);
    private Duration shutdownGrace = Duration.ofSeconds(30);
    private Duration publishedRetention = Duration.ofDays(7);
    private Duration maintenanceInterval = Duration.ofMinutes(1);
    private int cleanupBatchSize = 100;
    private Duration failureBackoff = Duration.ofSeconds(5);
    private int relayMaxAttempts = 10;
    private Map<String, String> routes = new LinkedHashMap<>();
    private List<String> immediateBusinessBoundaryDestinations = new ArrayList<>();
    private List<String> relayBusinessBoundaryDestinations = new ArrayList<>();
    private List<String> consumerBindings = new ArrayList<>();
    private int consumerMaxAttempts = 3;
    private Duration consumerBackoffInitial = Duration.ofMillis(100);
    private Duration consumerBackoffMax = Duration.ofSeconds(1);

    public boolean isRelayEnabled() {
        return relayEnabled;
    }

    public void setRelayEnabled(boolean relayEnabled) {
        this.relayEnabled = relayEnabled;
    }

    public String getRelayInstanceId() {
        return relayInstanceId;
    }

    public int getRelayBatchSize() {
        return relayBatchSize;
    }

    public void setRelayBatchSize(int relayBatchSize) {
        if (relayBatchSize < 1) {
            throw new IllegalArgumentException("relayBatchSize must be positive");
        }
        this.relayBatchSize = relayBatchSize;
    }

    public Duration getRelayPollInterval() {
        return relayPollInterval;
    }

    public void setRelayPollInterval(Duration relayPollInterval) {
        this.relayPollInterval = requirePositive(relayPollInterval, "relayPollInterval");
    }

    public Duration getClaimLease() {
        return claimLease;
    }

    public void setClaimLease(Duration claimLease) {
        this.claimLease = requirePositive(claimLease, "claimLease");
    }

    public Duration getLeaseSafetyMargin() {
        return leaseSafetyMargin;
    }

    public void setLeaseSafetyMargin(Duration leaseSafetyMargin) {
        this.leaseSafetyMargin = requirePositive(leaseSafetyMargin, "leaseSafetyMargin");
    }

    public Duration getBrokerAckTimeout() {
        return brokerAckTimeout;
    }

    public void setBrokerAckTimeout(Duration brokerAckTimeout) {
        this.brokerAckTimeout = requirePositive(brokerAckTimeout, "brokerAckTimeout");
    }

    public Duration getDatabaseStateUpdateBudget() {
        return databaseStateUpdateBudget;
    }

    public void setDatabaseStateUpdateBudget(Duration databaseStateUpdateBudget) {
        this.databaseStateUpdateBudget =
                requirePositive(databaseStateUpdateBudget, "databaseStateUpdateBudget");
    }

    public Duration getRelayRunBudget() {
        return relayRunBudget;
    }

    public void setRelayRunBudget(Duration relayRunBudget) {
        this.relayRunBudget = requirePositive(relayRunBudget, "relayRunBudget");
    }

    public Duration getShutdownGrace() {
        return shutdownGrace;
    }

    public void setShutdownGrace(Duration shutdownGrace) {
        this.shutdownGrace = requirePositive(shutdownGrace, "shutdownGrace");
    }

    public Duration getPublishedRetention() {
        return publishedRetention;
    }

    public void setPublishedRetention(Duration publishedRetention) {
        this.publishedRetention = requirePositive(publishedRetention, "publishedRetention");
    }

    public Duration getMaintenanceInterval() {
        return maintenanceInterval;
    }

    public void setMaintenanceInterval(Duration maintenanceInterval) {
        this.maintenanceInterval = requirePositive(maintenanceInterval, "maintenanceInterval");
    }

    public int getCleanupBatchSize() {
        return cleanupBatchSize;
    }

    public void setCleanupBatchSize(int cleanupBatchSize) {
        if (cleanupBatchSize < 1) {
            throw new IllegalArgumentException("cleanupBatchSize must be positive");
        }
        this.cleanupBatchSize = cleanupBatchSize;
    }

    public Duration getFailureBackoff() {
        return failureBackoff;
    }

    public int getRelayMaxAttempts() {
        return relayMaxAttempts;
    }

    public void setRelayMaxAttempts(int relayMaxAttempts) {
        if (relayMaxAttempts < 1) {
            throw new IllegalArgumentException("relayMaxAttempts must be positive");
        }
        this.relayMaxAttempts = relayMaxAttempts;
    }

    public void setFailureBackoff(Duration failureBackoff) {
        if (failureBackoff == null || failureBackoff.isNegative()) {
            throw new IllegalArgumentException("failureBackoff must not be negative");
        }
        this.failureBackoff = failureBackoff;
    }

    public Map<String, String> getRoutes() {
        return routes;
    }

    public void setRoutes(Map<String, String> routes) {
        this.routes = routes == null ? new LinkedHashMap<>() : new LinkedHashMap<>(routes);
    }

    public List<String> getConsumerBindings() {
        return consumerBindings;
    }

    public List<String> getRelayBusinessBoundaryDestinations() {
        return relayBusinessBoundaryDestinations;
    }

    public void setRelayBusinessBoundaryDestinations(List<String> destinations) {
        this.relayBusinessBoundaryDestinations = destinations == null ? new ArrayList<>() : new ArrayList<>(destinations);
    }

    public List<String> getImmediateBusinessBoundaryDestinations() {
        return immediateBusinessBoundaryDestinations;
    }

    public void setImmediateBusinessBoundaryDestinations(List<String> destinations) {
        this.immediateBusinessBoundaryDestinations = destinations == null ? new ArrayList<>() : new ArrayList<>(destinations);
    }

    public void setConsumerBindings(List<String> consumerBindings) {
        this.consumerBindings =
                consumerBindings == null ? new ArrayList<>() : new ArrayList<>(consumerBindings);
    }

    public int getConsumerMaxAttempts() {
        return consumerMaxAttempts;
    }

    public void setConsumerMaxAttempts(int consumerMaxAttempts) {
        if (consumerMaxAttempts < 1) {
            throw new IllegalArgumentException("consumerMaxAttempts must be positive");
        }
        this.consumerMaxAttempts = consumerMaxAttempts;
    }

    public Duration getConsumerBackoffInitial() {
        return consumerBackoffInitial;
    }

    public void setConsumerBackoffInitial(Duration consumerBackoffInitial) {
        this.consumerBackoffInitial = requirePositive(consumerBackoffInitial, "consumerBackoffInitial");
    }

    public Duration getConsumerBackoffMax() {
        return consumerBackoffMax;
    }

    public void setConsumerBackoffMax(Duration consumerBackoffMax) {
        this.consumerBackoffMax = requirePositive(consumerBackoffMax, "consumerBackoffMax");
    }

    public void validateRelayInvariant() {
        Duration worstBatch = brokerAckTimeout
                .multipliedBy(relayBatchSize)
                .plus(databaseStateUpdateBudget)
                .plus(leaseSafetyMargin);
        if (leaseSafetyMargin.compareTo(claimLease) >= 0
                || worstBatch.compareTo(claimLease) > 0
                || worstBatch.compareTo(shutdownGrace) > 0) {
            throw new IllegalStateException(
                    "relay batch ACK budget must fit within claimLease and shutdownGrace");
        }
    }

    private static Duration requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
