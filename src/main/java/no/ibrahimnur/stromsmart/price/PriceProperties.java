package no.ibrahimnur.stromsmart.price;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Innstillinger for innhenting av priser (stromsmart.prices.* i application.yaml).
 *
 * @param baseUrl      adressen til pris-API-et
 * @param userAgent    hvem som spør, så de som driver API-et kan se hvem som bruker det
 * @param historyDays  hvor mange dager bakover som skal finnes i databasen
 * @param importEvery  hvor ofte jobben ser etter nye priser
 * @param pause        pause mellom kall mot API-et, så vi ikke belaster det unødig
 */
@Validated
@ConfigurationProperties("stromsmart.prices")
public record PriceProperties(
        URI baseUrl,
        @NotBlank String userAgent,
        @Min(0) @Max(366) int historyDays,
        Duration importEvery,
        Duration pause) {
}
