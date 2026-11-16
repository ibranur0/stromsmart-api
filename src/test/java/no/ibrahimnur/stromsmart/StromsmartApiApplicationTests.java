package no.ibrahimnur.stromsmart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import no.ibrahimnur.stromsmart.price.Area;
import no.ibrahimnur.stromsmart.price.Price;
import no.ibrahimnur.stromsmart.price.PriceImporter;
import no.ibrahimnur.stromsmart.price.PriceSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * Hele appen mot en ekte PostgreSQL i Docker. Innhentingen lagrer priser, og API-et
 * leser dem og planlegger ut fra dem. Bare pris-API-et på nett er byttet ut.
 */
@SpringBootTest(properties = {"stromsmart.prices.import-enabled=false", "stromsmart.prices.history-days=1", "stromsmart.prices.pause=0s"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, StromsmartApiApplicationTests.FixedClock.class})
class StromsmartApiApplicationTests {

    /** Klokken står på 15. november 2026 kl. 10:00 norsk tid. */
    static final Instant NOW = ZonedDateTime.of(2026, 11, 15, 10, 0, 0, 0, Area.ZONE).toInstant();

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, Area.ZONE);
        }
    }

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private PriceImporter importer;

    @MockitoBean
    private PriceSource source;

    /** Hver time koster like mange kroner som klokkeslettet, så den billigste timen er tidlig på dagen. */
    private static List<Price> pricesFor(Area area, LocalDate date) {
        List<Price> out = new ArrayList<>();
        Instant start = Area.startOf(date);
        for (int hour = 0; hour < 24; hour++) {
            Instant from = start.plus(Duration.ofHours(hour));
            out.add(new Price(area, from, from.plus(Duration.ofHours(1)), BigDecimal.valueOf(hour + 1), BigDecimal.ONE, BigDecimal.TEN));
        }
        return out;
    }

    @Test
    void importedPricesAreServedWithVat() {
        when(source.fetch(any(), any())).thenAnswer(call -> pricesFor(call.getArgument(0), call.getArgument(1)));
        importer.importMissing();

        var today = mvc.get().uri("/api/v1/prices/NO1");
        assertThat(today).hasStatusOk().bodyJson().extractingPath("$.intervals.length()").isEqualTo(24);
        assertThat(today).bodyJson().extractingPath("$.intervals[0].start").isEqualTo("2026-11-15T00:00:00+01:00");
        assertThat(today).bodyJson().extractingPath("$.intervals[0].price").isEqualTo(1.25);
        assertThat(today).bodyJson().extractingPath("$.includesVat").isEqualTo(true);

        // Nord-Norge betaler ikke mva.
        assertThat(mvc.get().uri("/api/v1/prices/NO4")).bodyJson().extractingPath("$.intervals[0].price").isEqualTo(1.0);

        // Gårsdagen er også hentet, og tidligere dager kan bufres lenge.
        assertThat(mvc.get().uri("/api/v1/prices/NO1/2026-11-14"))
                .hasStatusOk()
                .hasHeader("Cache-Control", "max-age=86400");

        // Kl. 10 er det for tidlig for morgendagens priser.
        assertThat(mvc.get().uri("/api/v1/prices/NO1/2026-11-16")).hasStatus(404);

        // Billigste time fra nå (kl. 10) og ut dagen er kl. 10.
        assertThat(mvc.post().uri("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"area": "NO1", "mode": "WINDOW", "kwh": 1, "hours": 1, "includeVat": false}
                        """))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.blocks[0].start").isEqualTo("2026-11-15T10:00:00+01:00");
    }

    @Test
    void healthAndDocumentationAreAvailable() {
        assertThat(mvc.get().uri("/actuator/health")).hasStatusOk().bodyJson().extractingPath("$.status").isEqualTo("UP");
        assertThat(mvc.get().uri("/v3/api-docs")).hasStatusOk().bodyText().contains("Strømsmart API", "/api/v1/plans");
    }
}
