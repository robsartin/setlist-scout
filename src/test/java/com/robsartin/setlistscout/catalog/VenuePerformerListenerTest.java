package com.robsartin.setlistscout.catalog;

import com.robsartin.setlistscout.shared.events.VenuePerformerSeen;
import com.robsartin.setlistscout.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testcontainers-backed TDD for {@link VenuePerformerListener} (#206 Task 4): turns a {@link
 * VenuePerformerSeen} event into a PENDING_REVIEW/VENUE_EXPANSION {@link Artist} candidate,
 * calling the listener's {@code on} method directly against a real Postgres -- same shape as
 * {@code VenueScanRunnerTest}/{@code RelationDiscoveredFlowTest} (real DB needed to prove {@code
 * ArtistRepository#insertIfAbsent}'s {@code ON CONFLICT} actually no-ops, which a mock can't).
 * <p>
 * Deliberately does NOT go through {@code ApplicationEventPublisher} -- this class proves the
 * LISTENER's own logic (dedup / status handling / owner scoping); it does not, and cannot, prove
 * that {@code VenueScanRunner}'s publish site actually commits and triggers this listener for
 * real. {@code VenuePerformerSeenFlowTest} is the required real-path test for that (ADR-0024: a
 * test that never goes through the actual publish site is a false green for the "publish with no
 * committing transaction is silently dropped" bug class).
 * <p>
 * <b>{@code listener} IS the {@code @Autowired} Spring-managed bean, and every assertion below
 * goes through {@link #awaitUntil}/{@link #awaitAbsence}, never a bare read right after {@code
 * .on(...)}.</b> {@code @ApplicationModuleListener} composes both {@code @Async} (dispatches the
 * call to a background executor and returns immediately) AND {@code @Transactional(REQUIRES_NEW)}
 * (opens the transaction {@link ArtistRepository#insertIfAbsent} needs -- that method has no
 * {@code @Transactional} of its own, by design, and relies on an already-transactional caller).
 * Both only apply through the proxy Spring hands back for the {@code @Autowired} field; this was
 * confirmed empirically, the hard way, in two stages while building this test:
 * <ol>
 *   <li>Calling {@code .on(...)} on the autowired proxy and asserting on the very next line (the
 *   brief's own given-test shape) raced the background dispatch -- {@code
 *   createsCandidateForUnmatchedPerformer}/{@code isIdempotent} both saw zero rows where one was
 *   expected, with no exception surfaced (an async void method's exception is swallowed by the
 *   default {@code AsyncUncaughtExceptionHandler}, not propagated to the caller).</li>
 *   <li>"Fixing" that by hand-building {@code new VenuePerformerListener(artistRepository)} (a
 *   plain, unproxied POJO, so {@code @Async} is inert and the call runs synchronously) traded that
 *   bug for a worse one: EVERY test then failed with {@code InvalidDataAccessApiUsageException:
 *   Executing an update/delete query} -- {@code insertIfAbsent}'s native {@code @Modifying} query
 *   had no transaction at all, because the hand-built instance also has no {@code
 *   @Transactional(REQUIRES_NEW)} advice.</li>
 * </ol>
 * The only configuration that gets both a transaction AND a result worth asserting on is the real
 * proxy plus a bounded poll -- exactly {@code RelationDiscoveredFlowTest}'s established idiom for
 * driving an {@code @ApplicationModuleListener} in a test.
 * <p>
 * The rejected-performer case ({@link #doesNotResurrectRejected()}) is load-bearing, not a nice-to-
 * have: a venue calendar re-lists the same comedian every scan cycle, so if a REJECTED artist
 * could be resurrected to PENDING_REVIEW, a rejection would be undone on the very next scan and
 * reappear in the review queue forever.
 */
@SpringBootTest
@Testcontainers
class VenuePerformerListenerTest extends AbstractPostgresIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String OWNER = "venue-performer@example.com";

    @Autowired
    private ArtistRepository artistRepository;

    @Autowired
    private VenuePerformerListener listener;

    @BeforeEach
    void setUp() {
        // Container/context is shared across every test method in this class -- clear first, or a
        // prior method's committed row collides with this one's (owner, normalized_name).
        artistRepository.deleteAll();
    }

    private Artist seedArtist(String owner, String name, ArtistStatus status, ArtistSource source) {
        Artist artist = new Artist(name, source, status, null, null);
        artist.setOwner(owner);
        return artistRepository.save(artist);
    }

    @Test
    @DisplayName("an unmatched performer becomes exactly one PENDING_REVIEW artist sourced VENUE_EXPANSION")
    void createsCandidateForUnmatchedPerformer() {
        listener.on(new VenuePerformerSeen(OWNER, "Nick Mullen"));

        // NOTE: the brief's own snippet assigned this to `Artist` via
        // findByOwnerAndNormalizedName, which returns Optional<ArtistNameStatusView> -- a
        // projection interface with no getSource() and no relation to Artist. That does not
        // compile. findByOwnerAndName resolves the exact row insertIfAbsent just created (see its
        // own javadoc) and returns a real Artist, so getSource() is available for this assertion.
        Artist created = awaitUntil(
                () -> artistRepository.findByOwnerAndName(OWNER, "Nick Mullen").orElse(null),
                a -> a != null);
        assertThat(created).as("candidate persisted via the real (async) listener").isNotNull();
        assertThat(created.getStatus()).isEqualTo(ArtistStatus.PENDING_REVIEW);
        assertThat(created.getSource()).isEqualTo(ArtistSource.VENUE_EXPANSION);
    }

    @Test
    @DisplayName("a performer already in the catalog is left completely alone")
    void doesNotTouchExistingArtist() {
        seedArtist(OWNER, "Nick Mullen", ArtistStatus.APPROVED, ArtistSource.SEED_LIST);
        listener.on(new VenuePerformerSeen(OWNER, "nick MULLEN"));

        // No positive signal to wait on here (a row for this owner already exists before the
        // call), so this bounds how long we give the async listener to have created a SECOND row
        // before checking the negative property -- same NEGATIVE_PROOF_TIMEOUT awaitAbsence uses
        // everywhere else in this suite for "this must never happen" checks.
        List<Artist> all = awaitAbsence(() -> artistRepository.findByOwner(OWNER), rows -> rows.size() > 1);
        assertThat(all).as("no second row created for the case-variant name").hasSize(1);
        Artist existing = all.get(0);
        assertThat(existing.getName()).isEqualTo("Nick Mullen");
        assertThat(existing.getStatus()).isEqualTo(ArtistStatus.APPROVED);
        assertThat(existing.getSource()).isEqualTo(ArtistSource.SEED_LIST);
    }

    @Test
    @DisplayName("a rejected performer is not resurrected as a new candidate")
    void doesNotResurrectRejected() {
        seedArtist(OWNER, "Nick Mullen", ArtistStatus.REJECTED, ArtistSource.VENUE_EXPANSION);
        listener.on(new VenuePerformerSeen(OWNER, "Nick Mullen"));

        // insertIfAbsent is ON CONFLICT DO NOTHING -- there is no UPDATE clause anywhere in this
        // path, so the existing row's status cannot change by construction; the only way this
        // listener could go wrong is by inserting a SECOND row. Same wait rationale as
        // doesNotTouchExistingArtist above.
        //
        // KNOWN, CONFIRMED BLIND SPOT (mutation-tested, not theoretical): this assertion alone
        // cannot distinguish "insertIfAbsent cleanly no-op'd" from "some other write attempt threw
        // a DataIntegrityViolationException against the same (owner, normalized_name) constraint
        // and got silently swallowed by @Async's default exception handler" -- both leave exactly
        // one REJECTED row, because Postgres's own unique-constraint rollback happens to produce
        // the same visible end state as a graceful ON CONFLICT DO NOTHING no-op when there is only
        // ONE write in the listener's transaction. Proved this by temporarily replacing
        // insertIfAbsent with a plain artistRepository.save(...) here: this test (and
        // doesNotTouchExistingArtist) stayed GREEN. Only VenuePerformerSeenFlowTest's real-path
        // rejected-performer test catches that mutation, by asserting awaitQuiescence(jdbcTemplate)
        // returns exactly 0 -- Modulith only stamps event_publication.completion_date on a
        // listener's successful return, so a thrown/rolled-back transaction leaves it incomplete
        // even though the artist table looks identical either way. A direct .on(...) call here
        // never goes through publishEvent, so there is no event_publication row for THIS test to
        // wait on at all -- closing this gap requires the real publish path, not a tighter wait
        // here.
        List<Artist> all = awaitAbsence(() -> artistRepository.findByOwner(OWNER), rows -> rows.size() > 1);
        assertThat(all).as("no second row created for the rejected performer").hasSize(1);
        assertThat(all.get(0).getStatus()).isEqualTo(ArtistStatus.REJECTED);
    }

    @Test
    @DisplayName("the same performer seen twice creates exactly one candidate")
    void isIdempotent() {
        listener.on(new VenuePerformerSeen(OWNER, "Nick Mullen"));
        listener.on(new VenuePerformerSeen(OWNER, "Nick Mullen"));

        // Two-phase wait, deliberately: first give the (possibly concurrent -- @Async's default
        // executor can run the two calls on different threads at once, which is exactly the
        // concurrent-insert case ON CONFLICT DO NOTHING exists to serialize) calls generous time
        // to land at least one row, THEN spend a short additional bounded window confirming a
        // second row never appears. A single short poll risks a false pass if it happens to
        // sample between "zero landed" and "a duplicate is about to land"; a single long poll on
        // "size > 1" alone would spend the full positive timeout on every green run instead of
        // returning the moment the first row appears.
        awaitUntil(() -> artistRepository.findByOwner(OWNER), rows -> !rows.isEmpty());
        List<Artist> all = awaitAbsence(() -> artistRepository.findByOwner(OWNER), rows -> rows.size() > 1);
        assertThat(all).hasSize(1);
    }

    @Test
    @DisplayName("a performer seen for one owner never creates an artist for another")
    void isOwnerScoped() {
        listener.on(new VenuePerformerSeen("a@example.com", "Nick Mullen"));
        List<Artist> otherOwnerRows = awaitAbsence(
                () -> artistRepository.findByOwner("b@example.com"), rows -> !rows.isEmpty());
        assertThat(otherOwnerRows).isEmpty();
    }
}
