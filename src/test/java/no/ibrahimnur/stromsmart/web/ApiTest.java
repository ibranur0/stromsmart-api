package no.ibrahimnur.stromsmart.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import no.ibrahimnur.stromsmart.plan.PlanController;
import no.ibrahimnur.stromsmart.plan.Planner;
import no.ibrahimnur.stromsmart.price.Area;
import no.ibrahimnur.stromsmart.price.NoPricesException;
import no.ibrahimnur.stromsmart.price.PriceController;
import no.ibrahimnur.stromsmart.price.PriceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** API-et sett utenfra: adresser, JSON, feilsvar og CORS. Prisene kommer fra en falsk PriceService. */
@WebMvcTest({PriceController.class, PlanController.class})
class ApiTest {

    private static final Instant MIDNIGHT = OffsetDateTime.parse("2026-01-15T00:00:00+01:00").toInstant();

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private PriceService prices;

    @BeforeEach
    void dayWithPrices() {
        List<Planner.Interval> day = new ArrayList<>();
        double[] values = {5, 4, 1, 1, 2, 9, 9, 3};
        for (int hour = 0; hour < values.length; hour++) {
            Instant start = MIDNIGHT.plus(Duration.ofHours(hour));
            day.add(new Planner.Interval(start, start.plus(Duration.ofHours(1)), values[hour]));
        }
        when(prices.forPlanning(any(), anyBoolean())).thenReturn(day);
        when(prices.now()).thenReturn(MIDNIGHT.plus(Duration.ofMinutes(30)));
        when(prices.today()).thenReturn(LocalDate.of(2026, 1, 15));
    }

    @Test
    void listsTheFiveAreas() {
        assertThat(mvc.get().uri("/api/v1/areas"))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$[*].code").asArray().containsExactly("NO1", "NO2", "NO3", "NO4", "NO5");
    }

    @Test
    void unknownAreaGivesANorwegianProblemDetail() {
        assertThat(mvc.get().uri("/api/v1/prices/NO9"))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .extractingPath("$.detail").asString().contains("Ukjent prisområde 'NO9'");
    }

    @Test
    void dayWithoutPricesGives404() {
        when(prices.day(eq(Area.NO1), eq(LocalDate.of(2026, 1, 17)), anyBoolean()))
                .thenThrow(new NoPricesException("Prisene for 2026-01-17 er ikke publisert ennå."));

        assertThat(mvc.get().uri("/api/v1/prices/NO1/2026-01-17"))
                .hasStatus(HttpStatus.NOT_FOUND)
                .bodyJson()
                .extractingPath("$.title").isEqualTo("Ingen priser");
    }

    @Test
    void cheapestWindowInNorwegianTime() {
        var result = mvc.post().uri("/api/v1/plans")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"area": "NO1", "mode": "WINDOW", "kwh": 2, "hours": 2}
                        """);

        assertThat(result).hasStatusOk().bodyJson().extractingPath("$.blocks[0].start").isEqualTo("2026-01-15T02:00:00+01:00");
        assertThat(result).bodyJson().extractingPath("$.cost").isEqualTo(2.0);
        assertThat(result).bodyJson().extractingPath("$.saving").isEqualTo(5.0);
    }

    @Test
    void flexibleChargingWithDeadline() {
        assertThat(mvc.post().uri("/api/v1/plans")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"area": "NO1", "mode": "FLEXIBLE", "kwh": 11, "kw": 11, "deadline": "2026-01-15T02:00:00+01:00"}
                        """))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.blocks[0].start").isEqualTo("2026-01-15T01:00:00+01:00");
    }

    @Test
    void missingPowerIsExplained() {
        assertThat(mvc.post().uri("/api/v1/plans")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"area": "NO1", "mode": "FLEXIBLE", "kwh": 11}
                        """))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .extractingPath("$.detail").asString().contains("FLEXIBLE krever effekt");
    }

    @Test
    void deadlineInThePastIsRejected() {
        assertThat(mvc.post().uri("/api/v1/plans")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"area": "NO1", "mode": "WINDOW", "kwh": 2, "hours": 2, "deadline": "2026-01-14T07:00:00+01:00"}
                        """))
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson()
                .extractingPath("$.detail").isEqualTo("Fristen må være frem i tid.");
    }

    @Test
    void impossiblePlanGives422() {
        assertThat(mvc.post().uri("/api/v1/plans")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"area": "NO1", "mode": "WINDOW", "kwh": 2, "hours": 4, "deadline": "2026-01-15T01:00:00+01:00"}
                        """))
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson()
                .extractingPath("$.title").isEqualTo("Kan ikke planlegge");
    }

    @Test
    void portfolioMayCallTheApiFromTheBrowser() {
        assertThat(mvc.options().uri("/api/v1/plans")
                .header("Origin", "http://127.0.0.1:8138")
                .header("Access-Control-Request-Method", "POST"))
                .hasStatusOk()
                .hasHeader("Access-Control-Allow-Origin", "http://127.0.0.1:8138");
    }

    @Test
    void otherSitesMayNot() {
        assertThat(mvc.options().uri("/api/v1/plans")
                .header("Origin", "https://annen-side.example")
                .header("Access-Control-Request-Method", "POST"))
                .hasStatus(HttpStatus.FORBIDDEN);
    }
}
