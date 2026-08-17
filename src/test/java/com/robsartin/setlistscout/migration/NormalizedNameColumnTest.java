package com.robsartin.setlistscout.migration;

import com.robsartin.setlistscout.catalog.Artist;
import com.robsartin.setlistscout.catalog.ArtistNameNormalizer;
import com.robsartin.setlistscout.catalog.ArtistRepository;
import com.robsartin.setlistscout.catalog.ArtistSource;
import com.robsartin.setlistscout.catalog.ArtistStatus;
import com.robsartin.setlistscout.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #176. Every LIVE write to {@code artist} must carry the normalizer's output in
 * {@code normalized_name}, whichever path created the row -- the native-insert case is the one a
 * JPA lifecycle callback would silently miss -- and the column itself must carry the NOT NULL and
 * owner-first index constraints the migration's performance claim depends on.
 *
 * <p>This does NOT cover the migration's one-time backfill of rows that already existed before
 * V19 ran -- {@code @SpringBootTest} auto-migrates a fresh container straight to latest, so there
 * is no way to seed a row before V19 from within this class. See
 * {@link NormalizedNameBackfillMigrationTest} for that: it drives Flyway directly so it can seed
 * pre-V19 rows and assert what the backfill actually wrote.
 */
@SpringBootTest
@Testcontainers
class NormalizedNameColumnTest extends AbstractPostgresIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String OWNER = "rob@example.com";

    @Autowired private ArtistRepository artistRepository;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        artistRepository.deleteAll();
    }

    private String storedNormalizedName(String name) {
        return jdbc.queryForObject(
                "SELECT normalized_name FROM artist WHERE owner = ? AND name = ?",
                String.class, OWNER, name);
    }

    @Test
    @DisplayName("the JPA save path stores the normalizer's output")
    void savePathPopulatesNormalizedName() {
        Artist artist = new Artist("Wilco", ArtistSource.SEED_LIST, ArtistStatus.SEED, null, null);
        artist.setOwner(OWNER);
        artistRepository.save(artist);

        assertThat(storedNormalizedName("Wilco")).isEqualTo(ArtistNameNormalizer.normalize("Wilco"));
    }

    @Test
    @DisplayName("the NATIVE insertIfAbsent path stores it too -- a @PrePersist alone would miss this")
    // insertIfAbsent is a @Modifying native query with no @Transactional of its own -- calling it
    // directly here needs an ambient transaction supplied by the test, same as ArtistRepositoryTest.
    @Transactional
    void nativeInsertPopulatesNormalizedName() {
        String name = "Paul Quinichette - John Coltrane Quintet";
        artistRepository.insertIfAbsent(OWNER, name, ArtistNameNormalizer.normalize(name),
                ArtistSource.SEED_LIST.name(), ArtistStatus.SEED.name(), null, null, Instant.now());

        assertThat(storedNormalizedName(name)).isEqualTo(ArtistNameNormalizer.normalize(name));
        // The hyphen-spacing fold (#157) is what makes this differ from a plain lowercase.
        assertThat(storedNormalizedName(name)).isEqualTo("paul quinichette-john coltrane quintet");
    }

    @Test
    @DisplayName("unicode folding survives the round trip -- en dash and curly quote")
    @Transactional
    void unicodeFoldingIsStored() {
        String enDash = "Only Murders in the Building – Cast";
        String curly = "Charlie Parker’s Re-boppers";
        for (String name : new String[] {enDash, curly}) {
            artistRepository.insertIfAbsent(OWNER, name, ArtistNameNormalizer.normalize(name),
                    ArtistSource.SEED_LIST.name(), ArtistStatus.SEED.name(), null, null, Instant.now());
        }

        assertThat(storedNormalizedName(enDash)).isEqualTo(ArtistNameNormalizer.normalize(enDash));
        assertThat(storedNormalizedName(curly)).isEqualTo(ArtistNameNormalizer.normalize(curly));
        // Proof it is not merely a lowercase copy: the en dash became a hyphen.
        assertThat(storedNormalizedName(enDash)).contains("-cast").doesNotContain("–");
    }

    @Test
    @DisplayName("a non-Latin name keeps its characters -- normalization must not ASCII-strip")
    @Transactional
    void nonLatinNamePreserved() {
        String hebrew = "החברה";
        artistRepository.insertIfAbsent(OWNER, hebrew, ArtistNameNormalizer.normalize(hebrew),
                ArtistSource.SEED_LIST.name(), ArtistStatus.SEED.name(), null, null, Instant.now());

        assertThat(storedNormalizedName(hebrew)).isEqualTo(hebrew).isNotBlank();
    }

    @Test
    @DisplayName("the column is NOT NULL and indexed owner-first, by the exact index #176 depends on")
    void columnIsNotNullAndIndexed() {
        Integer nullable = jdbc.queryForObject(
                "SELECT CASE WHEN is_nullable = 'YES' THEN 1 ELSE 0 END FROM information_schema.columns "
                        + "WHERE table_name = 'artist' AND column_name = 'normalized_name'",
                Integer.class);
        assertThat(nullable).as("normalized_name must be NOT NULL").isZero();

        // Pinned by name AND column order -- a single-column index on normalized_name, or a
        // composite index in the wrong column order, would each satisfy a looser "does some index
        // mention this column" check while missing the whole point of #176: "Owner first: every
        // lookup is owner-scoped" (see V19's javadoc). containsIgnoringCase tolerates Postgres's
        // own casing of the definition; the parenthesized column list is what actually pins the order.
        List<String> indexDefs = jdbc.queryForList(
                "SELECT indexdef FROM pg_indexes WHERE tablename = 'artist' "
                        + "AND indexname = 'idx_artist_owner_normalized_name'",
                String.class);
        assertThat(indexDefs).as("idx_artist_owner_normalized_name must exist").hasSize(1);
        assertThat(indexDefs.get(0))
                .as("owner first, then normalized_name -- every lookup is owner-scoped")
                .containsIgnoringCase("(owner, normalized_name)");
    }
}
