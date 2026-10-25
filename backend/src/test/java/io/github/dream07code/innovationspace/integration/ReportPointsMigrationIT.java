package io.github.dream07code.innovationspace.integration;
import io.github.dream07code.testsupport.MysqlTestSupport;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;
import static org.junit.jupiter.api.Assertions.*;
@Testcontainers
class ReportPointsMigrationIT {
 @Container static final MySQLContainer MYSQL=MysqlTestSupport.newMysqlContainer();
 @Test void V4CreatesReportTaskReviewLedgerWithoutPersonalSeeds()throws Exception{
  MysqlTestSupport.migrate(MYSQL);
  try(var c=DriverManager.getConnection(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());var s=c.createStatement()){
   for(String table:new String[]{"report","report_version","report_scoring_task","report_review","point_ledger"})assertEquals(0,count(s,table),table);
   assertEquals(15,version(s));
   assertEquals(7,count(s,"direction"));
   var error=assertThrows(java.sql.SQLException.class,()->s.executeUpdate("INSERT INTO report(member_id,direction_id,report_type,report_date,status,current_version,created_at,updated_at) VALUES (999,1,'DAILY',CURDATE(),'DRAFT',1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))"));assertEquals("23000",error.getSQLState());assertTrue(error.getMessage().contains("fk_report_member"));
  }
 }
 private int count(java.sql.Statement s,String table)throws Exception{try(var r=s.executeQuery("SELECT COUNT(*) FROM "+table)){r.next();return r.getInt(1);}}
 private int version(java.sql.Statement s)throws Exception{try(var r=s.executeQuery("SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1")){r.next();return Integer.parseInt(r.getString(1));}}
}
