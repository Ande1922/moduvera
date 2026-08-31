package io.github.ande1922.moduvera.reference.order.design.persistence;

import java.sql.Connection;
import java.sql.SQLException;

@FunctionalInterface
interface JdbcConnectionFactory {

    Connection open() throws SQLException;
}
