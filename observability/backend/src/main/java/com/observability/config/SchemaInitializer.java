package com.observability.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;

/**
 * Ensures all tables in schema.sql exist at startup.
 * Since spring.sql.init.mode=never, we manually execute schema.sql
 * with CREATE TABLE IF NOT EXISTS statements (idempotent).
 */
@Slf4j
@Component
public class SchemaInitializer {

    @Autowired
    private DataSource dataSource;

    @EventListener(ApplicationReadyEvent.class)
    public void initializeSchema() {
        try (Connection conn = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(conn, new ClassPathResource("schema.sql"));
            log.info("[SchemaInitializer] Schema initialization completed successfully");
        } catch (Exception e) {
            log.warn("[SchemaInitializer] Schema initialization failed (tables may already exist): {}", e.getMessage());
        }
    }
}
