package com.friends.common.db;

import java.util.Properties;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

public final class DbUtil {
    private DbUtil() {}

    /**
     * Create a DataSource (HikariCP-backed). Expects either MySQL properties (mysql.url/mysql.user/mysql.password)
     * or a sqlite.file property for a local SQLite fallback.
     */
    public static DataSource createDataSource(Properties props) {
        HikariConfig config = new HikariConfig();
        String mysqlUrl = props.getProperty("mysql.url");
        if (mysqlUrl != null && !mysqlUrl.isBlank()) {
            config.setJdbcUrl(mysqlUrl);
            config.setUsername(props.getProperty("mysql.user"));
            config.setPassword(props.getProperty("mysql.password"));
            config.setDriverClassName("com.mysql.cj.jdbc.Driver");
        } else {
            String sqliteFile = props.getProperty("sqlite.file", "friends.db");
            config.setJdbcUrl("jdbc:sqlite:" + sqliteFile);
            config.setDriverClassName("org.sqlite.JDBC");
        }
        config.setMaximumPoolSize(Integer.parseInt(props.getProperty("db.pool.size", "10")));
        return new HikariDataSource(config);
    }
}
