package com.robsartin.setlistscout.scan;

import com.robsartin.setlistscout.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Issue #230: {@code show_event}'s real unique-index shape, proven against actual Postgres rather
 * than assumed. Before this issue, production carried a THIRD unique constraint on {@code
 * show_event} that omitted {@code owner} entirely -- so any given concert could exist for only one
 * owner in the whole system -- alongside a redundant exact duplicate of the correct, declared one.
 * {@code DropShowEventOwnerlessAndRedundantConstraintsMigrationTest} proves the migration itself
 * (V29) against a hand-built fixture; this class proves the OUTCOME against the app's own real,
 * fully-migrated schema -- the one every other Testcontainers test in this suite also boots
 * against -- so a future entity change that silently reintroduces an owner-less constraint fails
 * here too, not just in the dedicated migration test.
 */
@SpringBootTest
@Testcontainers
class ShowRepositoryTest extends AbstractPostgresIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private ShowRepository showRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static Show newShow(String owner, LocalDateTime eventDateTime) {
        Show show = new Show("Brandi Carlile - The Human Tour", eventDateTime, "Moody Center ATX", "Austin",
                BigDecimal.valueOf(45), "ticketmaster", "https://tickets.example/1", Show.Kind.MUSIC);
        show.setOwner(owner);
        return show;
    }

    @Test
    @DisplayName("issue #230: two different owners can hold the exact same show")
    void twoDifferentOwnersCanHoldTheSameShow() {
        // Distinct, test-method-local owners -- not shared class constants: this class (like
        // HiddenShowSurvivesRescanTest) shares one Postgres container/Spring context across all its
        // test methods with no @BeforeEach cleanup, and findByOwnerOrderByEventDateTimeAsc is
        // unfiltered by date, so a reused owner string could pick up another method's leftover rows.
        String ownerA = "multi-owner-a@example.com";
        String ownerB = "multi-owner-b@example.com";
        LocalDateTime eventDateTime = LocalDateTime.now().plusDays(20).truncatedTo(ChronoUnit.SECONDS);

        assertThatCode(() -> {
            showRepository.save(newShow(ownerA, eventDateTime));
            showRepository.save(newShow(ownerB, eventDateTime));
        }).as("the owner-less constraint that used to reject the second owner's row is gone")
                .doesNotThrowAnyException();

        assertThat(showRepository.findByOwnerOrderByEventDateTimeAsc(ownerA)).hasSize(1);
        assertThat(showRepository.findByOwnerOrderByEventDateTimeAsc(ownerB)).hasSize(1);
    }

    @Test
    @DisplayName("the same owner still cannot hold a duplicate show")
    void theSameOwnerStillCannotHoldADuplicate() {
        String owner = "dup-owner@example.com"; // test-method-local, see the note above
        LocalDateTime eventDateTime = LocalDateTime.now().plusDays(21).truncatedTo(ChronoUnit.SECONDS);
        showRepository.save(newShow(owner, eventDateTime));

        assertThatThrownBy(() -> showRepository.saveAndFlush(newShow(owner, eventDateTime)))
                .as("the declared owner-scoped constraint still rejects a true duplicate")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("issue #230: show_event carries exactly one unique constraint")
    void showEventHasExactlyOneUniqueConstraint() {
        List<String> uniqueConstraints = jdbcTemplate.queryForList(
                "SELECT conname FROM pg_constraint WHERE contype = 'u' AND conrelid = 'show_event'::regclass",
                String.class);

        assertThat(uniqueConstraints)
                .as("a future entity change must not be able to silently reintroduce an owner-less "
                        + "or redundant constraint")
                .containsExactly("show_event_owner_artist_name_event_date_time_venue_name_key");
    }
}
