package dev.btc.core.island.sql;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;

/**
 * The SQL flavours the island store speaks.
 *
 * <p>Only three statements actually differ between the two — table creation, secondary index
 * creation and the idempotent insert. Everything else is written in plain unquoted SQL that both
 * accept, which is why there is no quoting helper here: none of our identifiers is a reserved word
 * in either engine, so avoiding quotes altogether removes the difference instead of abstracting it.
 */
public enum SqlDialect {

    MYSQL,

    /**
     * PostgreSQL, the primary target.
     *
     * <p>Differs from MySQL in the two ways that bite silently: {@code INSERT IGNORE} does not exist
     * (the equivalent is {@code ON CONFLICT DO NOTHING}), and a non-unique index cannot be declared
     * inline in {@code CREATE TABLE}.
     */
    POSTGRESQL;

    /**
     * Reads the flavour off a live connection.
     *
     * @param connection an open connection
     * @return the matching dialect
     * @throws SQLException                  when the metadata cannot be read
     * @throws UnsupportedOperationException when the product is neither MySQL/MariaDB nor PostgreSQL
     */
    public static SqlDialect detect(Connection connection) throws SQLException {
        String product = connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
        if (product.contains("postgre")) {
            return POSTGRESQL;
        }
        if (product.contains("mysql") || product.contains("maria")) {
            return MYSQL;
        }
        throw new UnsupportedOperationException("Unsupported database for island ownership: " + product);
    }
}
