CREATE TABLE t_accounts
(
    id           uuid PRIMARY KEY,
    workspace_id uuid        NOT NULL,
    name         varchar(60) NOT NULL,
    currency     char(3)     NOT NULL,
    icon         varchar(60),
    archived     boolean     NOT NULL,
    recorded_at  timestamp   NOT NULL,

    CONSTRAINT fk_t_accounts_workspace_id
        FOREIGN KEY (workspace_id) REFERENCES t_workspaces (id) ON DELETE CASCADE
);

CREATE INDEX idx_t_events_workspace_id_aggregate_id_id
    ON t_events (workspace_id, aggregate_id, id DESC);
