CREATE TABLE t_events
(
    id             bigint    GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    workspace_id   uuid      NOT NULL,
    aggregate_type text      NOT NULL,
    aggregate_id   uuid      NOT NULL,
    event_type     text      NOT NULL,
    payload        jsonb     NOT NULL,
    occurred_at    timestamp NOT NULL,
    recorded_at    timestamp NOT NULL
);

CREATE INDEX idx_t_events_workspace_id_id ON t_events (workspace_id, id);
