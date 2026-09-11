CREATE TABLE t_balance_anchors
(
    id           uuid PRIMARY KEY,
    workspace_id uuid           NOT NULL,
    account_id   uuid           NOT NULL,
    value        numeric(19, 4) NOT NULL,
    occurred_at  timestamp      NOT NULL,
    recorded_at  timestamp      NOT NULL,

    CONSTRAINT fk_t_balance_anchors_workspace_id
        FOREIGN KEY (workspace_id) REFERENCES t_workspaces (id) ON DELETE CASCADE
);

CREATE INDEX idx_t_balance_anchors_workspace_id_account_id_occurred_at
    ON t_balance_anchors (workspace_id, account_id, occurred_at DESC);