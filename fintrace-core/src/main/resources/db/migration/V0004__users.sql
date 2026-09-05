CREATE TABLE t_users
(
    id          uuid      PRIMARY KEY,
    external_id text      NOT NULL,
    username    varchar(60) NOT NULL,
    created_at  timestamp NOT NULL,

    CONSTRAINT uq_t_users_external_id UNIQUE (external_id),
    CONSTRAINT uq_t_users_username UNIQUE (username)
);

INSERT INTO t_users (id, external_id, username, created_at)
VALUES (uuidv7(), 'stub:testuser', 'testuser', now());