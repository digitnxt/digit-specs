package org.digit.idgen;

import org.digit.idgen.config.IdgenProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(IdgenProperties.class)
public class IdgenApplication {

    public static void main(String[] args) {
        SpringApplication.run(IdgenApplication.class, args);
    }
}
