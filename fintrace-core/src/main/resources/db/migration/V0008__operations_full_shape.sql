ALTER TABLE t_operations
    ADD COLUMN kind varchar(10) NOT NULL,
    ADD COLUMN account_id     uuid        NOT NULL,
    ADD COLUMN category_id    uuid,         -- null for transfer legs
    ADD COLUMN transfer_id    uuid,         -- shared by the two legs of a transfer
    ADD COLUMN counterpart_id uuid,         -- the other leg
    ADD COLUMN comment        varchar(255),
    ADD COLUMN external_ref   varchar(60);

ALTER TABLE t_operations
    ADD CONSTRAINT chk_t_operations_kind
        CHECK (kind IN ('INCOME', 'EXPENSE', 'TRANSFER'));

CREATE INDEX idx_t_operations_workspace_id_occurred_at
    ON t_operations (workspace_id, occurred_at);
CREATE INDEX idx_t_operations_workspace_id_account_id_occurred_at
    ON t_operations (workspace_id, account_id, occurred_at);
CREATE INDEX idx_t_operations_workspace_id_category_id
    ON t_operations (workspace_id, category_id);
