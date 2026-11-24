package cn.edu.campus.hospitality.shared;

import java.sql.Clob;
import java.sql.ResultSetMetaData;
import java.sql.Timestamp;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Adapts JDBC updates and result sets to the application's database row format. */
@Component
public class Db {
  private final JdbcTemplate jdbc;

  /** Creates the database adapter using the application's configured JDBC template. */
  public Db(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Executes an update using bound argument values and returns the affected row count. */
  public int update(String sql, Object... args) {
    return jdbc.update(sql, args);
  }

  /** Returns query results with camel-case field names and normalized date and text values. */
  public List<Map<String, Object>> rows(String sql, Object... args) {
    return jdbc.query(
        sql,
        (rs, index) -> {
          Map<String, Object> row = new LinkedHashMap<>();
          ResultSetMetaData meta = rs.getMetaData();
          for (int i = 1; i <= meta.getColumnCount(); i++) {
            String label = meta.getColumnLabel(i).toLowerCase(Locale.ROOT);
            StringBuilder name = new StringBuilder();
            boolean upper = false;
            for (char c : label.toCharArray()) {
              if (c == '_') {
                upper = true;
              } else {
                name.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
              }
            }
            Object v = rs.getObject(i);
            if (v instanceof java.sql.Date d) {
              v = d.toLocalDate();
            }
            if (v instanceof Timestamp t) {
              v = t.toLocalDateTime();
            }
            if (v instanceof Clob c) {
              v = c.getSubString(1, (int) c.length());
            }
            row.put(name.toString(), v);
          }
          return row;
        },
        args);
  }

  /** Returns the first matching row when any rows are available. */
  public Optional<Map<String, Object>> find(String sql, Object... args) {
    return rows(sql, args).stream().findFirst();
  }

  /** Returns the first matching row or raises a not-found business error. */
  public Map<String, Object> one(String sql, Object... args) {
    return find(sql, args)
        .orElseThrow(() -> new BusinessException("NOT_FOUND", "记录不存在", Map.of(), 404));
  }

  /** Reads a count query result, returning zero for a null count. */
  public long count(String sql, Object... args) {
    Long v = jdbc.queryForObject(sql, Long.class, args);
    return v == null ? 0 : v;
  }

  /** Inserts one row using application-owned table and column names and bound values. */
  public void insert(String table, Map<String, Object> values) {
    // Table and column names are constants supplied by repositories, never request data.
    String columns = String.join(",", values.keySet());
    String placeholders = String.join(",", Collections.nCopies(values.size(), "?"));
    update(
        "INSERT INTO " + table + " (" + columns + ") VALUES (" + placeholders + ")",
        values.values().toArray());
  }
}
