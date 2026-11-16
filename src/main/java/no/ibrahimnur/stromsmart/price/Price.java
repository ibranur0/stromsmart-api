package no.ibrahimnur.stromsmart.price;

import java.math.BigDecimal;
import java.time.Instant;

/** Spotprisen i ett tidsintervall, uten mva. */
public record Price(Area area, Instant start, Instant end, BigDecimal nokPerKwh, BigDecimal eurPerKwh,
                    BigDecimal exchangeRate) {
}
