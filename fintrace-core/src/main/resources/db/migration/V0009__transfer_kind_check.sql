ALTER TABLE t_operations
    ADD CONSTRAINT chk_t_operations_transfer_kind
        CHECK ((kind = 'TRANSFER') = (transfer_id IS NOT NULL)),
    ADD CONSTRAINT chk_t_operations_transfer_counterpart
        CHECK ((transfer_id IS NOT NULL) = (counterpart_id IS NOT NULL)),
    ADD CONSTRAINT chk_t_operations_transfer_category
        CHECK ((transfer_id IS NOT NULL) = (category_id IS NULL));