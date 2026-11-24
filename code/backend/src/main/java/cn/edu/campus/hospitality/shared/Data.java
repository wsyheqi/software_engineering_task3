package cn.edu.campus.hospitality.shared;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Provides consistent conversions and validations for request and database maps. */
public final class Data {
  private Data() {}

  /** Builds an insertion-ordered map from alternating key and value arguments. */
  public static Map<String, Object> map(Object... pairs) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) {
      result.put((String) pairs[i], pairs[i + 1]);
    }
    return result;
  }

  /** Generates a UUID for a new business record. */
  public static String id() {
    return UUID.randomUUID().toString();
  }

  /** Converts a field to text and treats a missing value as empty. */
  public static String str(Map<String, ?> value, String key) {
    Object v = value.get(key);
    return v == null ? "" : v.toString();
  }

  /** Returns trimmed text or rejects a required field that is blank. */
  public static String required(Map<String, ?> value, String key) {
    String v = str(value, key).trim();
    require(!v.isEmpty(), "VALIDATION", "请填写 " + key);
    return v;
  }

  /** Reads a decimal field as an exact integer. */
  public static int integer(Map<String, ?> value, String key) {
    return decimal(value, key).intValueExact();
  }

  /** Reads a decimal field as an exact long integer. */
  public static long number(Map<String, ?> value, String key) {
    return decimal(value, key).longValueExact();
  }

  /** Reads a numeric field as a decimal, using zero when absent. */
  public static BigDecimal decimal(Map<String, ?> value, String key) {
    Object v = value.get(key);
    return v == null ? BigDecimal.ZERO : new BigDecimal(v.toString());
  }

  /** Recognizes a true Boolean or its case-insensitive string representation. */
  public static boolean bool(Map<String, ?> value, String key) {
    Object v = value.get(key);
    return Boolean.TRUE.equals(v) || "true".equalsIgnoreCase(String.valueOf(v));
  }

  /** Parses a required field as an ISO local date. */
  public static LocalDate date(Map<String, ?> value, String key) {
    return LocalDate.parse(required(value, key));
  }

  /** Parses a required field as an ISO local date and time. */
  public static LocalDateTime time(Map<String, ?> value, String key) {
    return LocalDateTime.parse(required(value, key));
  }

  /** Returns a nested map or an empty map when the field is absent or not an object. */
  @SuppressWarnings("unchecked")
  public static Map<String, Object> object(Map<String, ?> value, String key) {
    Object v = value.get(key);
    return v instanceof Map<?, ?> ? (Map<String, Object>) v : map();
  }

  /** Returns a list of nested maps or an empty list when the field is not an array. */
  @SuppressWarnings("unchecked")
  public static List<Map<String, Object>> objects(Map<String, ?> value, String key) {
    Object v = value.get(key);
    return v instanceof List<?> ? (List<Map<String, Object>>) v : List.of();
  }

  /** Converts list items to strings and treats a non-list field as an empty list. */
  public static List<String> strings(Map<String, ?> value, String key) {
    Object v = value.get(key);
    return v instanceof List<?> list ? list.stream().map(Object::toString).toList() : List.of();
  }

  /** Throws a business rejection with the supplied code if a rule fails. */
  public static void require(boolean condition, String code, String message) {
    if (!condition) {
      throw new BusinessException(code, message);
    }
  }

  /** Requires the order to have one of the statuses allowed by the operation. */
  public static void state(Map<String, ?> order, String... states) {
    require(Arrays.asList(states).contains(str(order, "status")), "STATE_CONFLICT", "当前订单状态不允许此操作");
  }
}
