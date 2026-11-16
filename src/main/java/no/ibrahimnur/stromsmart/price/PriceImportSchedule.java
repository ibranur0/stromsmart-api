package no.ibrahimnur.stromsmart.price;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Kjører innhentingen rett etter oppstart og så jevnlig. Ligger i en egen klasse
 * så testene kan slå av tidsplanen og kalle innhentingen selv.
 *
 * Innstillingen leses når appen starter. Med @ConditionalOnProperty blir den bestemt
 * allerede når native image bygges, og da kan ikke skyoppsettet slå av tidsplanen.
 */
@Component
class PriceImportSchedule {

    private final PriceImporter importer;
    private final boolean enabled;

    PriceImportSchedule(PriceImporter importer, @Value("${stromsmart.prices.import-enabled:true}") boolean enabled) {
        this.importer = importer;
        this.enabled = enabled;
    }

    @Scheduled(initialDelayString = "PT5S", fixedDelayString = "${stromsmart.prices.import-every}")
    void run() {
        if (enabled) {
            importer.importMissing();
        }
    }
}
