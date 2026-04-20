package io.quarkiverse.langchain4j.agentic.persistence.infinispan;

import java.util.Map;

import org.infinispan.commons.util.Version;

import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import io.smallrye.config.SmallRyeConfigBuilderCustomizer;

public class DevServicesConfigBuilderCustomizer implements SmallRyeConfigBuilderCustomizer {
    @Override
    public void configBuilder(final SmallRyeConfigBuilder builder) {
        builder.withSources(
                new PropertiesConfigSource(
                        Map.of("quarkus.infinispan-client.devservices.image-name",
                                "quay.io/infinispan/server:" + Version.getMajorMinor()),
                        "quarkus-langchain4j-agentic-persistence-infinispan", 50));
    }
}
