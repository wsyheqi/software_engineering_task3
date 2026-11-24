package cn.edu.campus.hospitality;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class DeletionMigrationTest {
  @Test
  void upgradingVersionTwoRetainsExistingAccountsHotelsOrdersAndFees() throws Exception {
    String url =
        "jdbc:h2:mem:upgrade-"
            + UUID.randomUUID()
            + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
    Flyway.configure().dataSource(url, "sa", "").target("2").load().migrate();
    try (var connection = DriverManager.getConnection(url, "sa", "");
        var sql = connection.createStatement()) {
      sql.executeUpdate(
          "INSERT INTO app_user(id,username,password_hash,display_name,role,version)"
              + " VALUES('existing-admin','existing_admin','original-hash','原管理员','ADMIN',7)");
      sql.executeUpdate(
          "INSERT INTO hotel(id,name,city,address,description,audited,evaluation)"
              + " VALUES('existing-hotel','原有酒店','南京','原有地址','已有资料',TRUE,90)");
      sql.executeUpdate(
          "INSERT INTO"
              + " booking_order(id,code,owner_id,hotel_id,start_date,end_date,"
              + "status,total,plan_json,rule_json,created_at)"
              + " VALUES('existing-order','EXISTING-ORDER','existing-admin','existing-hotel',"
              + "'2026-11-20','2026-11-22','CONFIRMED',1000,'{}','{}',CURRENT_TIMESTAMP)");
      sql.executeUpdate(
          "INSERT INTO"
              + " fee_record(id,order_id,category,amount,source_key,status,note,"
              + "proposed_by,created_at)"
              + " VALUES('existing-fee','existing-order','LODGING',1000,'original-fee',"
              + "'CONFIRMED','原费用','existing-admin',CURRENT_TIMESTAMP)");
    }
    Flyway current = Flyway.configure().dataSource(url, "sa", "").load();
    current.migrate();
    assertThat(current.info().current().getVersion().toString()).isEqualTo("3");
    try (var connection = DriverManager.getConnection(url, "sa", "");
        var sql = connection.createStatement()) {
      try (var row = sql.executeQuery("SELECT * FROM app_user WHERE id='existing-admin'")) {
        assertThat(row.next()).isTrue();
        assertThat(row.getString("password_hash")).isEqualTo("original-hash");
        assertThat(row.getLong("version")).isEqualTo(7);
        assertThat(row.getBoolean("enabled")).isTrue();
        assertThat(row.getTimestamp("deleted_at")).isNull();
      }
      try (var row = sql.executeQuery("SELECT * FROM hotel WHERE id='existing-hotel'")) {
        assertThat(row.next()).isTrue();
        assertThat(row.getString("name")).isEqualTo("原有酒店");
        assertThat(row.getTimestamp("deleted_at")).isNull();
      }
      try (var row =
          sql.executeQuery(
              "SELECT o.status,f.amount FROM booking_order o JOIN fee_record f ON f.order_id=o.id"
                  + " WHERE o.id='existing-order'")) {
        assertThat(row.next()).isTrue();
        assertThat(row.getString("status")).isEqualTo("CONFIRMED");
        assertThat(row.getBigDecimal("amount")).isEqualByComparingTo("1000");
      }
    }
  }
}
