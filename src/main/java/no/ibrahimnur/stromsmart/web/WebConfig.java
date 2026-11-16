package no.ibrahimnur.stromsmart.web;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WebConfig.CorsProperties.class)
class WebConfig implements WebMvcConfigurer {

    /** Nettsidene som får bruke API-et fra nettleseren (stromsmart.cors.allowed-origins). */
    @ConfigurationProperties("stromsmart.cors")
    record CorsProperties(List<String> allowedOrigins) {
    }

    private final CorsProperties cors;

    WebConfig(CorsProperties cors) {
        this.cors = cors;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(cors.allowedOrigins() == null ? new String[0] : cors.allowedOrigins().toArray(String[]::new))
                .allowedMethods("GET", "POST")
                .maxAge(3600);
    }

    /** Forsiden sender rett til dokumentasjonen, der API-et kan prøves. */
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/", "/swagger-ui.html");
    }

    @Bean
    OpenAPI openApi() {
        return new OpenAPI().info(new Info()
                .title("Strømsmart API")
                .version("v1")
                .description("""
                        Spotpriser for de fem prisområdene i Norge, hentet fra hvakosterstrommen.no og lagret \
                        i PostgreSQL, og en planlegger som finner det billigste tidspunktet å bruke strøm. \
                        Alle tider er i norsk tid, og prisene er i kroner per kWh."""));
    }
}
