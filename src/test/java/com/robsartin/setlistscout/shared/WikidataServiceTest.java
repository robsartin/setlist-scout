package com.robsartin.setlistscout.shared;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class WikidataServiceTest {

    private MockWebServer server;
    private WikidataService service;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        service = new WikidataService(server.url("/sparql").toString(), "setlist-scout-test/1.0");
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    /** A SPARQL results document with the given vars and rows, each row a list of raw values. */
    private static MockResponse sparql(String body) {
        return new MockResponse().setHeader("Content-Type", "application/sparql-results+json").setBody(body);
    }

    // ---- resolveByExactLabel -------------------------------------------------

    @Test
    @DisplayName("resolveByExactLabel returns the entity when exactly one matches the name")
    void shouldResolveWhenExactlyOneEntityMatches() {
        server.enqueue(sparql("""
                {"head": {"vars": ["ent", "entLabel", "entDescription"]},
                 "results": {"bindings": [
                   {"ent": {"type": "uri", "value": "http://www.wikidata.org/entity/Q41148"},
                    "entLabel": {"type": "literal", "value": "Martin Scorsese"},
                    "entDescription": {"type": "literal", "value": "American filmmaker (born 1942)"}}
                 ]}}
                """));

        Optional<WikidataEntity> match = service.resolveByExactLabel("Martin Scorsese");

        assertThat(match).contains(
                new WikidataEntity("Q41148", "Martin Scorsese", "American filmmaker (born 1942)"));
    }

    @Test
    @DisplayName("resolveByExactLabel refuses to choose when two entities share the name")
    void shouldRefuseWhenTwoEntitiesShareTheName() {
        server.enqueue(sparql("""
                {"head": {"vars": ["ent", "entLabel", "entDescription"]},
                 "results": {"bindings": [
                   {"ent": {"type": "uri", "value": "http://www.wikidata.org/entity/Q206112"},
                    "entLabel": {"type": "literal", "value": "Willie Nelson"},
                    "entDescription": {"type": "literal", "value": "American country musician (born 1933)"}},
                   {"ent": {"type": "uri", "value": "http://www.wikidata.org/entity/Q8021739"},
                    "entLabel": {"type": "literal", "value": "Willie Nelson"},
                    "entDescription": {"type": "literal", "value": "American boxer"}}
                 ]}}
                """));

        assertThat(service.resolveByExactLabel("Willie Nelson")).isEmpty();
    }

    @Test
    @DisplayName("resolveByExactLabel resolves when one QID is returned on several rows")
    void shouldResolveWhenOneQidRepeatsAcrossRows() {
        server.enqueue(sparql("""
                {"head": {"vars": ["ent", "entLabel", "entDescription"]},
                 "results": {"bindings": [
                   {"ent": {"type": "uri", "value": "http://www.wikidata.org/entity/Q1299"},
                    "entLabel": {"type": "literal", "value": "The Beatles"},
                    "entDescription": {"type": "literal", "value": "English pop rock band"}},
                   {"ent": {"type": "uri", "value": "http://www.wikidata.org/entity/Q1299"},
                    "entLabel": {"type": "literal", "value": "The Beatles"},
                    "entDescription": {"type": "literal", "value": "English pop rock band"}}
                 ]}}
                """));

        assertThat(service.resolveByExactLabel("The Beatles")).map(WikidataEntity::qid).contains("Q1299");
    }

    @Test
    @DisplayName("resolveByExactLabel is empty when nothing matches the name")
    void shouldBeEmptyWhenNothingMatches() {
        server.enqueue(sparql("""
                {"head": {"vars": ["ent"]}, "results": {"bindings": []}}
                """));

        assertThat(service.resolveByExactLabel("No Such Person")).isEmpty();
    }

    @Test
    @DisplayName("resolveByExactLabel is empty when the endpoint fails, rather than throwing")
    void shouldBeEmptyWhenEndpointFails() {
        server.enqueue(new MockResponse().setResponseCode(500));

        assertThat(service.resolveByExactLabel("Martin Scorsese")).isEmpty();
    }

    @Test
    @DisplayName("resolveByExactLabel escapes a quote in the name rather than injecting SPARQL")
    void shouldEscapeQuotesInTheName() throws InterruptedException {
        server.enqueue(sparql("""
                {"head": {"vars": ["ent"]}, "results": {"bindings": []}}
                """));

        service.resolveByExactLabel("Guns \" Roses");

        // Bounded: takeRequest() with no timeout blocks forever when no request was made, which
        // turns a plain assertion failure into a hung build.
        RecordedRequest request = server.takeRequest(5, TimeUnit.SECONDS);
        assertThat(request).as("a SPARQL request should have been sent").isNotNull();
        assertThat(request.getPath()).contains("Guns%20%5C%22%20Roses");
    }

    // ---- filmography --------------------------------------------------------

    @Test
    @DisplayName("filmography collapses a film's several release years to the earliest")
    void shouldCollapseSeveralReleaseYearsToTheEarliest() {
        server.enqueue(sparql("""
                {"head": {"vars": ["film", "filmLabel", "year", "role"]},
                 "results": {"bindings": [
                   {"film": {"type": "uri", "value": "http://www.wikidata.org/entity/Q154581"},
                    "filmLabel": {"type": "literal", "value": "Gangs of New York"},
                    "year": {"type": "literal", "value": "2003"},
                    "role": {"type": "literal", "value": "DIRECTED"}},
                   {"film": {"type": "uri", "value": "http://www.wikidata.org/entity/Q154581"},
                    "filmLabel": {"type": "literal", "value": "Gangs of New York"},
                    "year": {"type": "literal", "value": "2002"},
                    "role": {"type": "literal", "value": "DIRECTED"}}
                 ]}}
                """));

        List<WikidataFilmCredit> credits = service.filmography("Q41148");

        assertThat(credits).extracting("qid", "title", "releaseYear", "role")
                .containsExactly(tuple("Q154581", "Gangs of New York", 2002, "DIRECTED"));
    }

    @Test
    @DisplayName("filmography keeps one film's separate roles as separate credits")
    void shouldKeepSeparateRolesForOneFilm() {
        server.enqueue(sparql("""
                {"head": {"vars": ["film", "filmLabel", "year", "role"]},
                 "results": {"bindings": [
                   {"film": {"type": "uri", "value": "http://www.wikidata.org/entity/Q154581"},
                    "filmLabel": {"type": "literal", "value": "Gangs of New York"},
                    "year": {"type": "literal", "value": "2002"},
                    "role": {"type": "literal", "value": "DIRECTED"}},
                   {"film": {"type": "uri", "value": "http://www.wikidata.org/entity/Q154581"},
                    "filmLabel": {"type": "literal", "value": "Gangs of New York"},
                    "year": {"type": "literal", "value": "2003"},
                    "role": {"type": "literal", "value": "ACTED_IN"}}
                 ]}}
                """));

        assertThat(service.filmography("Q41148"))
                .extracting("qid", "releaseYear", "role")
                .containsExactlyInAnyOrder(
                        tuple("Q154581", 2002, "DIRECTED"),
                        tuple("Q154581", 2002, "ACTED_IN"));
    }

    @Test
    @DisplayName("filmography keeps a film that states no release year, with a null year")
    void shouldKeepAFilmWithNoReleaseYear() {
        server.enqueue(sparql("""
                {"head": {"vars": ["film", "filmLabel", "year", "role"]},
                 "results": {"bindings": [
                   {"film": {"type": "uri", "value": "http://www.wikidata.org/entity/Q999999"},
                    "filmLabel": {"type": "literal", "value": "Untitled Short"},
                    "role": {"type": "literal", "value": "DIRECTED"}}
                 ]}}
                """));

        assertThat(service.filmography("Q41148"))
                .extracting("title", "releaseYear")
                .containsExactly(tuple("Untitled Short", null));
    }

    @Test
    @DisplayName("filmography drops a row whose label is still the raw QID")
    void shouldDropARowWhoseLabelIsTheRawQid() {
        server.enqueue(sparql("""
                {"head": {"vars": ["film", "filmLabel", "year", "role"]},
                 "results": {"bindings": [
                   {"film": {"type": "uri", "value": "http://www.wikidata.org/entity/Q999999"},
                    "filmLabel": {"type": "literal", "value": "Q999999"},
                    "role": {"type": "literal", "value": "DIRECTED"}}
                 ]}}
                """));

        assertThat(service.filmography("Q41148")).isEmpty();
    }

    @Test
    @DisplayName("filmography is empty for someone with no film credits")
    void shouldBeEmptyForSomeoneWithNoFilmCredits() {
        server.enqueue(sparql("""
                {"head": {"vars": ["film"]}, "results": {"bindings": []}}
                """));

        assertThat(service.filmography("Q1155915")).isEmpty();
    }

    @Test
    @DisplayName("filmography is empty when the endpoint fails, rather than throwing")
    void shouldBeEmptyFilmographyWhenEndpointFails() {
        server.enqueue(new MockResponse().setResponseCode(429));

        assertThat(service.filmography("Q41148")).isEmpty();
    }

    // ---- describe -----------------------------------------------------------

    @Test
    @DisplayName("describe returns the label and description for a QID")
    void shouldDescribeAQid() {
        server.enqueue(sparql("""
                {"head": {"vars": ["entLabel", "entDescription"]},
                 "results": {"bindings": [
                   {"entLabel": {"type": "literal", "value": "Willie Nelson"},
                    "entDescription": {"type": "literal", "value": "American country musician (born 1933)"}}
                 ]}}
                """));

        assertThat(service.describe("Q206112")).contains(
                new WikidataEntity("Q206112", "Willie Nelson", "American country musician (born 1933)"));
    }

    @Test
    @DisplayName("describe refuses a QID that is not shaped like one")
    void shouldRefuseToDescribeAMalformedQid() {
        assertThat(service.describe("Special:Search")).isEmpty();
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    @DisplayName("filmography refuses a QID that is not shaped like one")
    void shouldRefuseAMalformedQid() {
        assertThat(service.filmography("Special:Search")).isEmpty();
        assertThat(server.getRequestCount()).isZero();
    }
}
