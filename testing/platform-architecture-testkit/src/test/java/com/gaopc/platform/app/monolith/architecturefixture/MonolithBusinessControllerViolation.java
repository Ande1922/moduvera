package com.gaopc.platform.app.monolith.architecturefixture;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MonolithBusinessControllerViolation {

    @GetMapping("/v1/forbidden-monolith-business-endpoint")
    String businessEndpoint() {
        return "forbidden";
    }
}
