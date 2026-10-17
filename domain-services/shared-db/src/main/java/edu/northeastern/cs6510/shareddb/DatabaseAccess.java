package edu.northeastern.cs6510.shareddb;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.Function;

/** Infrastructure-only transaction helper; domain repositories remain service-local. */
public final class DatabaseAccess {
    private DatabaseAccess() { }
    public static <T> T inTransaction(DataSource source, Function<Connection, T> action) {
        try (Connection connection = source.getConnection()) {
            connection.setAutoCommit(false);
            try { T value = action.apply(connection); connection.commit(); return value; }
            catch (RuntimeException failure) { connection.rollback(); throw failure; }
        } catch (SQLException failure) { throw new IllegalStateException("Database operation failed", failure); }
    }
}
