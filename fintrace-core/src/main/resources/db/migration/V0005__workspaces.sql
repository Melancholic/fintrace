CREATE TABLE t_workspaces
(
    id               uuid PRIMARY KEY,
    name             varchar(60) NOT NULL,
    status           varchar(60) NOT NULL,
    owner_id         uuid        NOT NULL,
    default_currency char(3)     NOT NULL,
    created_at       timestamp   NOT NULL,
    updated_at       timestamp   NOT NULL,
    version          bigint      NOT NULL,
    deleted_at       timestamp,

    CONSTRAINT chk_t_workspaces_status
        CHECK (status IN ('NEW', 'ACTIVE', 'ARCHIVED', 'DELETED')),
    CONSTRAINT chk_t_workspaces_deleted_at
        CHECK ((status = 'DELETED') = (deleted_at IS NOT NULL)),
    CONSTRAINT fk_t_workspaces_owner_id
        FOREIGN KEY (owner_id) REFERENCES t_users (id)
);

ALTER TABLE t_events
    ADD CONSTRAINT fk_t_events_workspace_id
        FOREIGN KEY (workspace_id) REFERENCES t_workspaces (id) ON DELETE CASCADE;

ALTER TABLE t_operations
    ADD CONSTRAINT fk_t_operations_workspace_id
        FOREIGN KEY (workspace_id) REFERENCES t_workspaces (id) ON DELETE CASCADE;
