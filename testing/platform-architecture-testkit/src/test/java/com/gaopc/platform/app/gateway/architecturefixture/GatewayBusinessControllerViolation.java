package com.gaopc.platform.app.gateway.architecturefixture;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class GatewayBusinessControllerViolation {

    @GetMapping("/api/order/v1/orders")
    String proxyOrder() {
        return "duplicated-business-response";
    }
}
