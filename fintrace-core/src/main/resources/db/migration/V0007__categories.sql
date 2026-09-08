CREATE TABLE t_categories
(
    id           uuid PRIMARY KEY,
    workspace_id uuid        NOT NULL,
    parent_id    uuid,                 -- null only for the two roots
    name         varchar(60) NOT NULL,
    kind        varchar(10) NOT NULL,  -- INCOME / EXPENSE
    icon         varchar(60),
    archived     boolean     NOT NULL,
    system_code varchar(15),           --  INCOME_ROOT / INCOME_OTHERS / EXPENSE_ROOT / EXPENSE_OTHERS
    recorded_at  timestamp   NOT NULL,

    CONSTRAINT fk_t_categories_workspace_id
        FOREIGN KEY (workspace_id) REFERENCES t_workspaces (id) ON DELETE CASCADE
);

CREATE INDEX idx_t_categories_workspace_id_parent_id ON t_categories (workspace_id, parent_id);
CREATE UNIQUE INDEX idx_t_categories_workspace_id_system_code ON t_categories (workspace_id, system_code) WHERE system_code IS NOT NULL;
