package com.gaopc.platform.web;

import com.gaopc.platform.error.CodedException;
import java.util.Optional;
import org.springframework.http.HttpStatusCode;

@FunctionalInterface
public interface ProblemStatusContributor {

    Optional<HttpStatusCode> statusFor(CodedException exception);
}
