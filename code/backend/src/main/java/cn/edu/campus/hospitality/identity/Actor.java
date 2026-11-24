package cn.edu.campus.hospitality.identity;

import static cn.edu.campus.hospitality.shared.Data.map;
import static cn.edu.campus.hospitality.shared.Data.str;

import java.util.Map;

/** Represents the authenticated user and the business scope bound to the account. */
public record Actor(
    String id,
    String username,
    String name,
    String role,
    String hotelId,
    String guestId,
    String email) {
  /** Creates an actor from an account database row. */
  public static Actor from(Map<String, Object> row) {
    return new Actor(
        str(row, "id"),
        str(row, "username"),
        str(row, "displayName"),
        str(row, "role"),
        str(row, "hotelId"),
        str(row, "guestId"),
        str(row, "email"));
  }

  /** Tests whether the authenticated actor has the requested role. */
  public boolean is(String role) {
    return this.role.equals(role);
  }

  /** Returns the session-facing fields of this account. */
  public Map<String, Object> view() {
    return map(
        "id",
        id,
        "username",
        username,
        "name",
        name,
        "role",
        role,
        "hotelId",
        hotelId,
        "guestId",
        guestId);
  }
}
