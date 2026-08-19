package com.robsartin.setlistscout.scan;

import com.robsartin.setlistscout.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #166's whole premise, proven against real Postgres and the REAL production skip-vs-insert
 * path ({@link ScanUnitRunner#persistNew}) rather than assumed: hiding a show has to survive the
 * next scan re-discovering the exact same show, because shows aren't hand-entered -- every scan
 * re-discovers them from scratch.
 *
 * <p>{@code ScanUnitRunner#persistNew} already skips (never refreshes) a show that exists by
 * natural key ({@code owner, artistName, eventDateTime, venueName} -- the same {@code existsBy...}
 * check {@link ScanUnitRunnerTest#skipsDuplicateShow} pins), and that check doesn't look at
 * {@code hidden_at} at all -- so a hidden row is just as "already exists" as a visible one, and a
 * re-scan's freshly-scraped {@link Show} for the same gig is dropped on the floor without ever
 * touching the persisted row. Runs in CI (needs Docker).
 */
@SpringBootTest
@Testcontainers
class HiddenShowSurvivesRescanTest extends AbstractPostgresIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String OWNER = "rescan-hidden@example.com";

    @Autowired
    private ShowRepository showRepository;

    @Autowired
    private ScanUnitRunner scanUnitRunner;

    @Test
    void aHiddenShowIsSkippedNotRefreshedByARescanThatRediscoversIt() {
        LocalDateTime eventDateTime = LocalDateTime.now().plusDays(30).truncatedTo(ChronoUnit.SECONDS);
        Show original = new Show("Wilco", eventDateTime, "The Moody Center", "Austin",
                BigDecimal.valueOf(45), "ticketmaster", "https://tickets.example/original", Show.Kind.MUSIC);
        original.setOwner(OWNER);
        Long id = showRepository.save(original).getId();

        Show toHide = showRepository.findById(id).orElseThrow();
        toHide.setHiddenAt(Instant.now());
        showRepository.save(toHide);
        assertThat(showRepository.findById(id).orElseThrow().getHiddenAt()).isNotNull();

        // A later scan "rediscovers" the exact same gig: same natural key, but a genuinely new Show
        // instance (as a fresh scrape would produce), even with different incidental details (price,
        // ticket URL) -- proving the skip is keyed on identity, not object/reference equality.
        Show rediscovered = new Show("Wilco", eventDateTime, "The Moody Center", "Austin",
                BigDecimal.valueOf(50), "ticketmaster", "https://tickets.example/rediscovered", Show.Kind.MUSIC);

        int saved = scanUnitRunner.persistNew(OWNER, List.of(rediscovered));

        assertThat(saved).as("the rediscovered show is skipped, not inserted as a new row").isEqualTo(0);
        assertThat(showRepository.findByOwnerAndEventDateTimeBetweenOrderByEventDateTimeAsc(
                        OWNER, eventDateTime.minusDays(1), eventDateTime.plusDays(1)))
                .as("still exactly one row for this owner/artist/venue/time").hasSize(1);
        assertThat(showRepository.findById(id).orElseThrow().getHiddenAt())
                .as("the original row's hidden state is completely untouched by the rescan").isNotNull();
    }
}
