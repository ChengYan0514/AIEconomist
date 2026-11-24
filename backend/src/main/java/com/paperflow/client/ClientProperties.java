package com.paperflow.client;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;
import java.time.Duration;

@ConfigurationProperties("paperflow.client")
public record ClientProperties(@DefaultValue Citations citations, @DefaultValue Module qa, @DefaultValue Module writing) {
    public record Citations(@DefaultValue("stub") String provider,
                            @DefaultValue("http://127.0.0.1:8000") URI engineUrl,
                            @DefaultValue("PT10M") Duration requestTimeout,
                            @DefaultValue("PT1.5S") Duration pollInterval,
                            @DefaultValue("3") int maxActivePerUser,
                            @DefaultValue("4") int maxConcurrency) {
        public Citations {
            if (requestTimeout.isNegative() || requestTimeout.isZero() || pollInterval.isNegative()
                    || pollInterval.isZero() || maxActivePerUser < 1 || maxConcurrency < 1) {
                throw new IllegalArgumentException("Invalid client citation limits");
            }
        }
    }

    public record Module(boolean enabled, String engineUrl) {}
}
