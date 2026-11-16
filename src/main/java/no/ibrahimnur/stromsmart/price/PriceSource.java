package no.ibrahimnur.stromsmart.price;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Henter spotpriser fra hvakosterstrommen.no, som har en JSON-fil per dag og prisområde.
 * Morgendagens priser kommer rundt kl. 13. Før det svarer de 404.
 */
@Component
@RegisterReflectionForBinding(PriceSource.Row.class) // Jackson leser Row med refleksjon, også i native image
public class PriceSource {

    /** Ett intervall slik API-et sender det. */
    record Row(
            @JsonProperty("NOK_per_kWh") BigDecimal nokPerKwh,
            @JsonProperty("EUR_per_kWh") BigDecimal eurPerKwh,
            @JsonProperty("EXR") BigDecimal exchangeRate,
            @JsonProperty("time_start") OffsetDateTime start,
            @JsonProperty("time_end") OffsetDateTime end) {
    }

    private final RestClient client;

    public PriceSource(RestClient.Builder builder, PriceProperties properties) {
        this.client = builder
                .baseUrl(properties.baseUrl().toString())
                .defaultHeader("User-Agent", properties.userAgent())
                .build();
    }

    /** Prisene for en dag, eller en tom liste hvis de ikke er publisert ennå. */
    public List<Price> fetch(Area area, LocalDate day) {
        Row[] rows = client.get()
                .uri("/{year}/{monthAndDay}_{area}.json", day.getYear(), "%02d-%02d".formatted(day.getMonthValue(), day.getDayOfMonth()), area.name())
                .exchange((request, response) -> {
                    int status = response.getStatusCode().value();
                    if (status == 404) {
                        return new Row[0];
                    }
                    if (status >= 400) {
                        throw new PriceSourceException("Pris-API-et svarte %d for %s %s".formatted(status, area, day));
                    }
                    return response.bodyTo(Row[].class);
                });
        if (rows == null) {
            return List.of();
        }
        return Arrays.stream(rows)
                .map(row -> new Price(area, row.start().toInstant(), row.end().toInstant(),
                        row.nokPerKwh(), row.eurPerKwh(), row.exchangeRate()))
                .toList();
    }

    /** Pris-API-et svarte med en feil. */
    public static class PriceSourceException extends RuntimeException {
        public PriceSourceException(String message) {
            super(message);
        }
    }
}
