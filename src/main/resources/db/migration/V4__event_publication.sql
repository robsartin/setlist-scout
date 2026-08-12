-- Durable event-publication registry for Spring Modulith's JPA-backed event bus
-- (spring-modulith-starter-jpa -> spring-modulith-events-jpa's JpaEventPublication
-- entity, mapped by DefaultJpaEventPublication to table EVENT_PUBLICATION). Inert
-- until Phase B adds publishers/listeners -- this only wires up the persistence.
--
-- Column shape (types + nullability) was NOT guessed: it was captured by booting
-- the actual application with spring.jpa.hibernate.ddl-auto=update against a throwaway
-- Postgres and inspecting the table Hibernate generated from the real entity mapping
-- (org.springframework.modulith.events.jpa.JpaEventPublication / DefaultJpaEventPublication,
-- version 1.3.12 resolved from the project's BOM). That generated shape is the ground
-- truth ddl-auto=validate checks against -- see task-G5-report.md for the full trail.
CREATE TABLE IF NOT EXISTS event_publication (
    id                UUID NOT NULL,
    completion_date   TIMESTAMP(6) WITH TIME ZONE,
    event_type        VARCHAR(255),
    listener_id       VARCHAR(255),
    publication_date  TIMESTAMP(6) WITH TIME ZONE,
    serialized_event  VARCHAR(255),
    PRIMARY KEY (id)
);

-- Supporting indexes for the JPA repository's actual query shapes (JpaEventPublicationRepository):
-- findIncompletePublications()/-Before() filter+order by completion_date/publication_date, and
-- the by-event-and-listener lookup filters on (serialized_event, listener_id). Not required by
-- Hibernate's entity mapping (no @Table(indexes=...) on the entity), so ddl-auto=validate does not
-- check for these -- added purely for query performance, safe to have as extras.
CREATE INDEX IF NOT EXISTS event_publication_by_completion_date_idx
    ON event_publication (completion_date);
CREATE INDEX IF NOT EXISTS event_publication_by_event_and_listener_idx
    ON event_publication (serialized_event, listener_id);
