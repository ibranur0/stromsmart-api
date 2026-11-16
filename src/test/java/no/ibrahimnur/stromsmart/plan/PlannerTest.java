package no.ibrahimnur.stromsmart.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import no.ibrahimnur.stromsmart.plan.Planner.Block;
import no.ibrahimnur.stromsmart.plan.Planner.Interval;
import no.ibrahimnur.stromsmart.plan.Planner.Mode;
import no.ibrahimnur.stromsmart.plan.Planner.Task;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Samme tilfeller og tall som test_stromsmart.py i porteføljen.
 * Består begge, regner Java og Python likt.
 */
class PlannerTest {

    private static final Instant MIDNIGHT = OffsetDateTime.parse("2026-01-15T00:00:00+01:00").toInstant();
    private static final double CLOSE = 1e-7;

    /** En dag med gitte priser (kr/kWh), fra midnatt. */
    private static List<Interval> day(double... prices) {
        return dayIn(60, prices);
    }

    /** Som day(), men med intervaller på gitt antall minutter. */
    private static List<Interval> dayIn(int minutes, double... prices) {
        List<Interval> out = new ArrayList<>();
        for (int n = 0; n < prices.length; n++) {
            Instant start = MIDNIGHT.plus(Duration.ofMinutes((long) minutes * n));
            out.add(new Interval(start, start.plus(Duration.ofMinutes(minutes)), prices[n]));
        }
        return out;
    }

    private static Instant at(int hours, int minutes) {
        return MIDNIGHT.plus(Duration.ofHours(hours).plusMinutes(minutes));
    }

    @Nested
    class Window {
        private final List<Interval> prices = day(5, 4, 1, 1, 2, 9, 9, 3);

        @Test
        void findsCheapestContiguousHours() {
            var result = Planner.cheapestWindow(prices, 2, 2);
            assertThat(result.blocks().getFirst().start()).isEqualTo(at(2, 0));
            assertThat(result.cost()).isCloseTo(2.0, within(CLOSE));
        }

        @Test
        void windowMayStartInsideAnInterval() {
            // 1,5 timer: billigst å ta hele time 2 og halve time 3 (begge koster 1).
            assertThat(Planner.cheapestWindow(prices, 1.5, 1.5).cost()).isCloseTo(1.5, within(CLOSE));
        }

        @Test
        void partialWindowBeatsBoundaryStart() {
            // 1,5 timer: hele time 1 (pris 1) og halve time 2 (pris 2) = 2,0. Start 01:00.
            var result = Planner.cheapestWindow(day(9, 1, 2, 9), 1.5, 1.5);
            assertThat(result.blocks().getFirst().start()).isEqualTo(at(1, 0));
            assertThat(result.cost()).isCloseTo(2.0, within(CLOSE));
        }

        @Test
        void tooLongWindowIsRejected() {
            assertThatThrownBy(() -> Planner.cheapestWindow(prices, 9, 1)).isInstanceOf(PlanningException.class);
        }

        @Test
        void quarterHourPrices() {
            var result = Planner.cheapestWindow(dayIn(15, 4, 4, 1, 1, 1, 1, 4, 4), 1, 2);
            assertThat(result.blocks().getFirst().start()).isEqualTo(at(0, 30));
            assertThat(result.cost()).isCloseTo(2.0, within(CLOSE));
        }
    }

    @Nested
    class Slots {
        private final List<Interval> prices = day(5, 1, 9, 1, 2, 9);

        @Test
        void picksCheapestHoursEvenWhenApart() {
            var result = Planner.cheapestSlots(prices, 22, 11); // to timer
            assertThat(result.blocks()).hasSize(2);
            assertThat(result.cost()).isCloseTo(22.0, within(CLOSE));
        }

        @Test
        void adjacentHoursAreMerged() {
            var result = Planner.cheapestSlots(prices, 33, 11); // tre timer: 01, 03 og 04
            assertThat(result.blocks()).hasSize(2);
            assertThat(result.blocks().get(1).end()).isEqualTo(at(5, 0));
        }

        @Test
        void partialHour() {
            assertThat(Planner.cheapestSlots(prices, 16.5, 11).cost()).isCloseTo(16.5, within(CLOSE)); // 1,5 time
        }

        @Test
        void partialHourIsPlacedNextToTheFollowingBlock() {
            var result = Planner.cheapestSlots(day(9, 2, 1, 9), 16.5, 11); // hele time 2 og halve time 1
            assertThat(result.blocks()).containsExactly(new Block(at(1, 30), at(3, 0)));
        }

        @Test
        void neverMoreExpensiveThanAnyContiguousRun() {
            double flexible = Planner.cheapestSlots(prices, 22, 11).cost();
            for (int start = 0; start < 5; start++) {
                double run = Planner.costOfRun(prices, at(start, 0), 2, 11).orElseThrow();
                assertThat(flexible).isLessThanOrEqualTo(run + 1e-9);
            }
        }

        @Test
        void notEnoughTimeIsRejected() {
            assertThatThrownBy(() -> Planner.cheapestSlots(prices, 100, 11)).isInstanceOf(PlanningException.class);
        }
    }

    @Nested
    class Plans {
        private final List<Interval> prices = day(5, 4, 1, 1, 2, 9, 9, 3);
        private final Instant now = at(0, 30);

        @Test
        void savingIsComparedWithStartingNow() {
            var plan = Planner.plan(prices, now, new Task(Mode.WINDOW, 2, null, 2.0, null));
            // Start nå (00:30): 0,5 t * 5 + 1 t * 4 + 0,5 t * 1 = 7,0. Best: 2,0.
            assertThat(plan.baselineCost()).isCloseTo(7.0, within(CLOSE));
            assertThat(plan.saving()).isCloseTo(5.0, within(CLOSE));
            assertThat(plan.averagePrice()).isCloseTo(1.0, within(CLOSE));
        }

        @Test
        void deadlineLimitsTheSearch() {
            var plan = Planner.plan(prices, now, new Task(Mode.FLEXIBLE, 11, 11.0, null, at(2, 0)));
            assertThat(plan.blocks().getFirst().start()).isEqualTo(at(1, 0));
        }

        @Test
        void pastHoursAreIgnored() {
            var plan = Planner.plan(prices, at(5, 0), new Task(Mode.FLEXIBLE, 11, 11.0, null, null));
            assertThat(plan.blocks().getFirst().start()).isEqualTo(at(7, 0));
        }

        @Test
        void impossibleDeadlineIsRejected() {
            var task = new Task(Mode.WINDOW, 1, null, 4.0, at(1, 0));
            assertThatThrownBy(() -> Planner.plan(prices, now, task))
                    .isInstanceOf(PlanningException.class)
                    .hasMessageContaining("ikke nok tid");
        }

        @Test
        void noPricesInTheChosenPeriodIsRejected() {
            var task = new Task(Mode.FLEXIBLE, 1, 1.0, null, null);
            assertThatThrownBy(() -> Planner.plan(prices, at(9, 0), task)).isInstanceOf(PlanningException.class);
        }
    }
}
