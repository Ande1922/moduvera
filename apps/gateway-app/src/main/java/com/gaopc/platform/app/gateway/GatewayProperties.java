package com.gaopc.platform.app.gateway;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("gateway")
public class GatewayProperties {

    @NotNull
    private URI identityBaseUrl = URI.create("http://localhost:8081");

    @NotNull
    private URI orderBaseUrl = URI.create("http://localhost:8083");

    @NotBlank
    private String serviceId = "gateway";

    @NotBlank
    private String serviceSecret;

    @NotNull
    private Duration timeout = Duration.ofSeconds(3);

    private boolean orderTargetPreservesPrefix;

    public URI getIdentityBaseUrl() {
        return identityBaseUrl;
    }

    public void setIdentityBaseUrl(URI identityBaseUrl) {
        this.identityBaseUrl = identityBaseUrl;
    }

    public URI getOrderBaseUrl() {
        return orderBaseUrl;
    }

    public void setOrderBaseUrl(URI orderBaseUrl) {
        this.orderBaseUrl = orderBaseUrl;
    }

    public String getServiceId() {
        return serviceId;
    }

    public void setServiceId(String serviceId) {
        this.serviceId = serviceId;
    }

    public String getServiceSecret() {
        return serviceSecret;
    }

    public void setServiceSecret(String serviceSecret) {
        this.serviceSecret = serviceSecret;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public boolean isOrderTargetPreservesPrefix() {
        return orderTargetPreservesPrefix;
    }

    public void setOrderTargetPreservesPrefix(boolean orderTargetPreservesPrefix) {
        this.orderTargetPreservesPrefix = orderTargetPreservesPrefix;
    }
}
