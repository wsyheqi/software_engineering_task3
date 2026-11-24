package cn.edu.campus.hospitality.shared;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/** Serializes stored records and canonicalizes values for stable snapshot hashes. */
@Component
public class Json {
  private final ObjectMapper mapper;

  /** Copies the configured mapper and preserves decimal precision when reading records. */
  public Json(ObjectMapper mapper) {
    this.mapper = mapper.copy().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
  }

  /** Serializes a record or response into JSON for persistence. */
  public String write(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("JSON serialization failed", e);
    }
  }

  /** Parses stored JSON into a map while preserving decimal precision. */
  public Map<String, Object> read(String value) {
    try {
      return mapper.readValue(value, new TypeReference<>() {});
    } catch (Exception e) {
      throw new IllegalStateException("Stored JSON is invalid", e);
    }
  }

  /** Hashes a value after normalizing field order and equivalent numeric representations. */
  public String hash(Object value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(write(canonical(value)).getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private Object canonical(Object value) {
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> sorted = new TreeMap<>();
      map.forEach((k, v) -> sorted.put(k.toString(), canonical(v)));
      return sorted;
    }
    if (value instanceof Collection<?> list) {
      return list.stream().map(this::canonical).toList();
    }
    if (value instanceof Number n) {
      return new BigDecimal(n.toString()).stripTrailingZeros().toPlainString();
    }
    if (value instanceof java.time.temporal.TemporalAccessor) {
      return value.toString();
    }
    return value;
  }
}
