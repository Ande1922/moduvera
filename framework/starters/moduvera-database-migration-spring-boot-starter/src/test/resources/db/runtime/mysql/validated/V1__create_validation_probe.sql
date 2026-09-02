CREATE TABLE ${probeTable} (
    probe_id BIGINT NOT NULL PRIMARY KEY,
    probe_value VARCHAR(64) NOT NULL
);

INSERT INTO ${probeTable} (probe_id, probe_value)
VALUES (1, 'unchanged');
