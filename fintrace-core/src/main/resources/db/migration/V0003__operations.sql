CREATE TABLE t_operations
(
    id           uuid          PRIMARY KEY,
    workspace_id uuid          NOT NULL,
    amount       numeric(19, 4) NOT NULL,
    occurred_at  timestamp     NOT NULL,
    recorded_at  timestamp     NOT NULL
);
