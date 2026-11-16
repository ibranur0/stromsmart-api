package no.ibrahimnur.stromsmart;

import java.time.Clock;
import no.ibrahimnur.stromsmart.price.Area;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
@ImportRuntimeHints(NativeHints.class)
public class StromsmartApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(StromsmartApiApplication.class, args);
    }

    /** Klokken hentes herfra overalt, så testene kan stille den. */
    @Bean
    Clock clock() {
        return Clock.system(Area.ZONE);
    }
}
