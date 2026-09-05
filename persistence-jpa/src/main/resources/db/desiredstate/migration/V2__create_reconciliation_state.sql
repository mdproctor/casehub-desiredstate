CREATE TABLE ds_reconciliation_state (
    tenancy_id   VARCHAR(255) PRIMARY KEY,
    graph_json   TEXT NOT NULL,
    updated_at   TIMESTAMP NOT NULL
);
