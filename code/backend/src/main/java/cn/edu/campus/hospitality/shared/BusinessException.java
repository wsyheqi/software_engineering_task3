package cn.edu.campus.hospitality.shared;

import java.util.Map;

/** Describes a business rejection that can be returned to the client. */
public class BusinessException extends RuntimeException {
  private final String code;
  private final Map<String, Object> data;
  private final int httpStatus;

  /** Creates a conflict response without additional error data. */
  public BusinessException(String code, String message) {
    this(code, message, Map.of(), 409);
  }

  /** Creates a business rejection with the supplied HTTP status and response data. */
  public BusinessException(String code, String message, Map<String, Object> data, int status) {
    super(message);
    this.code = code;
    this.data = data;
    this.httpStatus = status;
  }

  /** Returns the stable business code used by clients to handle the rejection. */
  public String code() {
    return code;
  }

  /** Returns additional details supplied for this rejection. */
  public Map<String, Object> data() {
    return data;
  }

  /** Returns the HTTP status associated with this rejection. */
  public int httpStatus() {
    return httpStatus;
  }
}
