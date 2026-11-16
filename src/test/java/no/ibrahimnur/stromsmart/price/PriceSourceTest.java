package no.ibrahimnur.stromsmart.price;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.restclient.test.autoconfigure.RestClientTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.client.MockRestServiceServer;

@RestClientTest(PriceSource.class)
@EnableConfigurationProperties(PriceProperties.class)
@TestPropertySource(properties = {
        "stromsmart.prices.base-url=https://priser.test/api/v1/prices",
        "stromsmart.prices.user-agent=stromsmart-test"})
class PriceSourceTest {

    @Autowired
    private PriceSource source;

    @Autowired
    private MockRestServiceServer server;

    private static final LocalDate DAY = LocalDate.of(2026, 11, 5);

    @Test
    void readsADay() {
        server.expect(requestTo("https://priser.test/api/v1/prices/2026/11-05_NO1.json"))
                .andExpect(header("User-Agent", "stromsmart-test"))
                .andRespond(withSuccess("""
                        [{"NOK_per_kWh":1.63827,"EUR_per_kWh":0.155,"EXR":10.5695,
                          "time_start":"2026-11-05T00:00:00+01:00","time_end":"2026-11-05T01:00:00+01:00"},
                         {"NOK_per_kWh":-0.01,"EUR_per_kWh":-0.001,"EXR":10.5695,
                          "time_start":"2026-11-05T01:00:00+01:00","time_end":"2026-11-05T01:15:00+01:00"}]
                        """, MediaType.APPLICATION_JSON));

        var prices = source.fetch(Area.NO1, DAY);

        assertThat(prices).hasSize(2);
        assertThat(prices.getFirst().start()).isEqualTo(OffsetDateTime.parse("2026-11-05T00:00:00+01:00").toInstant());
        assertThat(prices.getFirst().nokPerKwh()).isEqualByComparingTo("1.63827");
        // Negative priser forekommer, og kvarter også.
        assertThat(prices.get(1).nokPerKwh()).isEqualByComparingTo(new BigDecimal("-0.01"));
        assertThat(prices.get(1).end()).isEqualTo(OffsetDateTime.parse("2026-11-05T01:15:00+01:00").toInstant());
        server.verify();
    }

    @Test
    void dayThatIsNotPublishedYetIsEmpty() {
        server.expect(requestTo("https://priser.test/api/v1/prices/2026/11-05_NO4.json"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(source.fetch(Area.NO4, DAY)).isEmpty();
    }

    @Test
    void serverErrorIsReported() {
        server.expect(requestTo("https://priser.test/api/v1/prices/2026/11-05_NO2.json")).andRespond(withServerError());

        assertThatThrownBy(() -> source.fetch(Area.NO2, DAY))
                .isInstanceOf(PriceSource.PriceSourceException.class)
                .hasMessageContaining("500");
    }
}
