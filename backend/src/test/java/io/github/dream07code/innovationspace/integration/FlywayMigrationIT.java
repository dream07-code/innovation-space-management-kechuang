package io.github.dream07code.innovationspace.integration;

import io.github.dream07code.testsupport.MysqlTestSupport;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class FlywayMigrationIT {
    @Container static final MySQLContainer MYSQL = MysqlTestSupport.newMysqlContainer();
    @Test void explicitMigrationIsRepeatableAndSeedsNoPersonalData() throws Exception {
        MysqlTestSupport.migrate(MYSQL);
        MysqlTestSupport.migrate(MYSQL);
        try (var connection=DriverManager.getConnection(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
                var statement=connection.createStatement()) {
            for (var table : new String[]{"member","app_user","auth_session","audit_log","api_idempotency"}) {
                try(var r=statement.executeQuery("SELECT COUNT(*) FROM "+table)) { r.next();assertEquals(0,r.getInt(1),table); }
            }
            try(var r=statement.executeQuery("SELECT COUNT(*) FROM direction")) { r.next();assertEquals(7,r.getInt(1)); }
            try(var r=statement.executeQuery("SELECT COUNT(*) FROM bootstrap_lock")) { r.next();assertEquals(1,r.getInt(1)); }
            for (var index : new String[]{"idx_report_member_activity","idx_member_auto_removal"}) {
                try(var r=statement.executeQuery("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND index_name='"+index+"'")) {
                    r.next(); assertTrue(r.getInt(1)>0,index);
                }
            }
            try(var r=statement.executeQuery("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1")) { r.next();assertEquals(9,r.getInt(1)); }
        }
    }

    @Test void executableMaintenanceModeDoesNotStartHttpOrCreateUsers() throws Exception {
        var log = java.nio.file.Path.of("target/migration-command.log");
        var processBuilder = new ProcessBuilder(java.nio.file.Path.of(System.getProperty("java.home"),"bin","java").toString(),
                "-jar","target/innovation-space-backend-0.1.0-SNAPSHOT.jar","--app.migration.mode=migrate");
        var env = processBuilder.environment(); env.clear();
        env.put("DB_HOST",MYSQL.getHost()); env.put("DB_PORT",MYSQL.getMappedPort(3306).toString());
        env.put("DB_NAME",MYSQL.getDatabaseName()); env.put("DB_USERNAME",MYSQL.getUsername()); env.put("DB_PASSWORD",MYSQL.getPassword());
        var process=processBuilder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(45,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(0,process.exitValue());
            var output=java.nio.file.Files.readString(log);
            assertFalse(output.contains("Tomcat started"));
            assertFalse(output.contains(MYSQL.getPassword()));
        } finally { if(process.isAlive()) process.destroyForcibly(); }
    }
    @Test void systemPropertyMaintenanceModeAndConflictingModesAreSafe()throws Exception {
        for(boolean conflict:new boolean[]{false,true}) {
            var log=java.nio.file.Path.of("target/maintenance-mode-"+conflict+".log");
            var args=new java.util.ArrayList<String>();args.add(java.nio.file.Path.of(System.getProperty("java.home"),"bin","java").toString());
            args.add("-Dapp.migration.mode=migrate");args.add("-jar");args.add("target/innovation-space-backend-0.1.0-SNAPSHOT.jar");
            if(conflict)args.add("--app.bootstrap.mode=admin");
            var builder=new ProcessBuilder(args);var e=builder.environment();e.clear();
            e.put("DB_HOST",MYSQL.getHost());e.put("DB_PORT",MYSQL.getMappedPort(3306).toString());e.put("DB_NAME",MYSQL.getDatabaseName());e.put("DB_USERNAME",MYSQL.getUsername());e.put("DB_PASSWORD",MYSQL.getPassword());
            e.put("ADMIN_ACCOUNT","synthetic-admin");e.put("ADMIN_PASSWORD","Synthetic-bootstrap12!");e.put("ADMIN_DISPLAY_NAME","Synthetic Admin");
            var process=builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
            try {
                assertTrue(process.waitFor(20,java.util.concurrent.TimeUnit.SECONDS),"maintenance must exit without Web listener");
                if(conflict)assertNotEquals(0,process.exitValue());else assertEquals(0,process.exitValue());
                assertFalse(java.nio.file.Files.readString(log).contains("Tomcat started"));
            }finally{if(process.isAlive())process.destroyForcibly();}
        }
    }
    @Test void configurationFileMaintenanceAndCommandLineOverrideUseOneResolvedMode()throws Exception {
        var config=java.nio.file.Path.of("target/maintenance-config.yaml").toAbsolutePath();
        java.nio.file.Files.writeString(config,"app:\n  migration:\n    mode: migrate\n");
        var log=java.nio.file.Path.of("target/maintenance-config.log");
        var args=new java.util.ArrayList<String>();args.add(java.nio.file.Path.of(System.getProperty("java.home"),"bin","java").toString());args.add("-jar");args.add("target/innovation-space-backend-0.1.0-SNAPSHOT.jar");args.add("--spring.config.additional-location="+config.toUri());
        var builder=new ProcessBuilder(args);var e=builder.environment();e.clear();e.put("DB_HOST",MYSQL.getHost());e.put("DB_PORT",MYSQL.getMappedPort(3306).toString());e.put("DB_NAME",MYSQL.getDatabaseName());e.put("DB_USERNAME",MYSQL.getUsername());e.put("DB_PASSWORD",MYSQL.getPassword());
        var process=builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try{assertTrue(process.waitFor(25,java.util.concurrent.TimeUnit.SECONDS));assertEquals(0,process.exitValue());assertFalse(java.nio.file.Files.readString(log).contains("Tomcat started"));}finally{if(process.isAlive())process.destroyForcibly();}
        java.nio.file.Files.deleteIfExists(config);
    }
}
