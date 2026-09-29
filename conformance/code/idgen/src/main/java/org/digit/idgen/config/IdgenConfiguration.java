package org.digit.idgen.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class IdgenConfiguration {

    @Bean
    Clock idgenClock(IdgenProperties properties) {
        return Clock.system(ZoneId.of(properties.timezone()));
    }
}
