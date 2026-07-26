package com.miningdim.caseopening.store;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/** Opens the case ledger beside the world save; production and GameTest share the same schema. */
public final class CaseDb {

    public static final String DB_FILE_NAME = "miningdim_cases.db";

    private CaseDb() {
    }

    public static CaseDaoSqlite open(MinecraftServer server) {
        Path path = server.getWorldPath(LevelResource.ROOT).resolve(DB_FILE_NAME);
        return connect("jdbc:sqlite:" + path.toString().replace('\\', '/'));
    }

    public static CaseDaoSqlite openInMemory() {
        return connect("jdbc:sqlite::memory:");
    }

    private static CaseDaoSqlite connect(String url) {
        try {
            Class.forName("org.sqlite.JDBC");
            Connection connection = DriverManager.getConnection(url);
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA foreign_keys=ON");
                statement.execute("PRAGMA busy_timeout=5000");
            }
            CaseDaoSqlite dao = new CaseDaoSqlite(connection);
            dao.initSchema();
            return dao;
        } catch (ClassNotFoundException | SQLException exception) {
            throw new CaseStoreException("failed to open case ledger " + url, exception);
        }
    }
}
