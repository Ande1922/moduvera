-- Local acceptance identities. These databases and passwords are deliberately isolated from production.
CREATE ROLE identity LOGIN PASSWORD 'identity-reference';
CREATE ROLE catalog LOGIN PASSWORD 'catalog-reference';
CREATE ROLE orders LOGIN PASSWORD 'order-reference';
CREATE ROLE inventory LOGIN PASSWORD 'inventory-reference';

CREATE DATABASE identity OWNER identity;
CREATE DATABASE catalog OWNER catalog;
CREATE DATABASE orders OWNER orders;
CREATE DATABASE inventory OWNER inventory;
