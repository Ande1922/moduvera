CREATE TABLE migration_order (
    position INTEGER NOT NULL,
    component_name VARCHAR(48) NOT NULL,
    dialect_name VARCHAR(16) NOT NULL
);

INSERT INTO migration_order (position, component_name, dialect_name)
VALUES (1, 'alpha', 'postgresql');
