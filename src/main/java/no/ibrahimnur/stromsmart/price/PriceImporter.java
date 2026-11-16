package no.ibrahimnur.stromsmart.price;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Sørger for at databasen har prisene for de siste dagene, i dag og i morgen (etter kl. 13),
 * for alle prisområdene. Bare dager som mangler hentes. Feiler et kall, prøver vi igjen neste runde.
 */
@Component
public class PriceImporter {

    private static final Logger log = LoggerFactory.getLogger(PriceImporter.class);

    /** Morgendagens priser publiseres rundt kl. 13. Før det er det ingen vits i å spørre. */
    static final LocalTime TOMORROW_PUBLISHED = LocalTime.of(13, 0);

    private final PriceSource source;
    private final PriceRepository repository;
    private final PriceProperties properties;
    private final Clock clock;

    public PriceImporter(PriceSource source, PriceRepository repository, PriceProperties properties, Clock clock) {
        this.source = source;
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    /** Henter alle dager som mangler. Returnerer hvor mange dager som ble lagret. */
    public int importMissing() {
        ZonedDateTime now = ZonedDateTime.now(clock).withZoneSameInstant(Area.ZONE);
        LocalDate today = now.toLocalDate();
        LocalDate last = now.toLocalTime().isBefore(TOMORROW_PUBLISHED) ? today : today.plusDays(1);
        int saved = 0;
        for (Area area : Area.values()) {
            for (LocalDate day = today.minusDays(properties.historyDays()); !day.isAfter(last); day = day.plusDays(1)) {
                if (repository.exists(area, Area.startOf(day), Area.startOf(day.plusDays(1)))) {
                    continue;
                }
                saved += fetchAndSave(area, day);
                pause();
            }
        }
        if (saved > 0) {
            log.info("Hentet priser for {} nye dager", saved);
        }
        return saved;
    }

    private int fetchAndSave(Area area, LocalDate day) {
        try {
            List<Price> prices = source.fetch(area, day);
            if (prices.isEmpty()) {
                return 0; // ikke publisert ennå
            }
            repository.saveAll(prices);
            return 1;
        } catch (RuntimeException e) {
            log.warn("Klarte ikke å hente priser for {} {}: {}", area, day, e.getMessage());
            return 0;
        }
    }

    private void pause() {
        try {
            Thread.sleep(properties.pause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
