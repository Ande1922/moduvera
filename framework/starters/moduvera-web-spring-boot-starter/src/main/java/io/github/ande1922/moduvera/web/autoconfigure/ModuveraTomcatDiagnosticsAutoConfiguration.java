package io.github.ande1922.moduvera.web.autoconfigure;

import io.github.ande1922.moduvera.web.TomcatRequestDiagnosticsValve;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = {"org.apache.catalina.valves.ValveBase",
        "org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory"})
public class ModuveraTomcatDiagnosticsAutoConfiguration {
    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> moduveraTomcatDiagnostics() {
        return factory -> factory.addEngineValves(new TomcatRequestDiagnosticsValve());
    }
}
