package io.github.dream07code.innovationspace.integration;

import io.github.dream07code.testsupport.MysqlTestSupport;
import java.nio.file.*;
import java.sql.DriverManager;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class AdminBootstrapIT {
 @Container static final MySQLContainer MYSQL=MysqlTestSupport.newMysqlContainer();
 ProcessBuilder command(Path log){
  var b=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-jar","target/innovation-space-backend-0.1.0-SNAPSHOT.jar","--app.bootstrap.mode=admin");
  var e=b.environment();e.clear();e.put("DB_HOST",MYSQL.getHost());e.put("DB_PORT",MYSQL.getMappedPort(3306).toString());e.put("DB_NAME",MYSQL.getDatabaseName());e.put("DB_USERNAME",MYSQL.getUsername());e.put("DB_PASSWORD",MYSQL.getPassword());
  return b.redirectErrorStream(true).redirectOutput(log.toFile());
 }
 @Test void explicitBootstrapRequiresSecretsAndDoesNotStartHttpOrOverwriteAdmin()throws Exception{
  MysqlTestSupport.migrate(MYSQL);
  var log=Path.of("target/admin-bootstrap.log");var missing=command(log).start();
  try{assertTrue(missing.waitFor(25,TimeUnit.SECONDS));assertNotEquals(0,missing.exitValue());}finally{if(missing.isAlive())missing.destroyForcibly();}
  for(String password:new String[]{"Synthetic-admin-password12!","Second-synthetic-password12!"}){
   var b=command(log);b.environment().put("ADMIN_ACCOUNT","synthetic-bootstrap");b.environment().put("ADMIN_PASSWORD",password);b.environment().put("ADMIN_DISPLAY_NAME","Synthetic Admin");
   var p=b.start();try{assertTrue(p.waitFor(25,TimeUnit.SECONDS));assertEquals(0,p.exitValue());}finally{if(p.isAlive())p.destroyForcibly();}
   var output=Files.readString(log);assertFalse(output.contains(password));assertFalse(output.contains("Tomcat started"));
  }
  try(var c=DriverManager.getConnection(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());var st=c.createStatement();var r=st.executeQuery("SELECT password_hash FROM app_user WHERE role='COLLEGE_ADMIN'")){
    r.next();assertTrue(new io.github.dream07code.innovationspace.security.PasswordService().matches("Synthetic-admin-password12!",r.getString(1)));assertFalse(new io.github.dream07code.innovationspace.security.PasswordService().matches("Second-synthetic-password12!",r.getString(1)));
  }
  try(var c=DriverManager.getConnection(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());var st=c.createStatement();var r=st.executeQuery("SELECT COUNT(*) FROM app_user WHERE role='COLLEGE_ADMIN'")){r.next();assertEquals(1,r.getInt(1));}
 }
}
