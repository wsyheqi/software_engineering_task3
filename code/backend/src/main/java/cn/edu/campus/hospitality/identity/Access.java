package cn.edu.campus.hospitality.identity;

import static cn.edu.campus.hospitality.shared.Data.str;

import cn.edu.campus.hospitality.audit.AuditService;
import cn.edu.campus.hospitality.shared.BusinessException;
import cn.edu.campus.hospitality.shared.Db;
import java.util.Arrays;
import java.util.Map;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/** Enforces role, responsibility, and hotel access to business objects. */
@Service
public class Access {
  private final Db db;
  private final AuditService audit;

  /** Creates the access service with account lookup and denial auditing. */
  public Access(Db db, AuditService audit) {
    this.db = db;
    this.audit = audit;
  }

  /** Resolves the signed-in user to an active business actor. */
  public Actor current() {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null || !auth.isAuthenticated() || auth.getName().equals("anonymousUser")) {
      throw new BusinessException("UNAUTHENTICATED", "请先登录", Map.of(), 401);
    }
    return byUsername(auth.getName());
  }

  /** Looks up an active account by its login name. */
  public Actor byUsername(String username) {
    return activeAccount(
        "SELECT * FROM app_user WHERE username=? AND enabled=TRUE AND deleted_at IS NULL",
        username);
  }

  /** Looks up an active account by its internal identifier. */
  public Actor byId(String actorId) {
    return activeAccount(
        "SELECT * FROM app_user WHERE id=? AND enabled=TRUE AND deleted_at IS NULL", actorId);
  }

  private Actor activeAccount(String sql, String value) {
    return db.find(sql, value)
        .map(Actor::from)
        .orElseThrow(
            () -> new BusinessException("UNAUTHENTICATED", "账号已停用或删除，请重新登录", Map.of(), 401));
  }

  /** Requires the actor to have one of the permitted roles. */
  public void roles(Actor a, String... roles) {
    check(a, Arrays.asList(roles).contains(a.role()), null, null, null, "当前角色无此操作权限");
  }

  /** Rejects an unauthorized operation and records its audit context. */
  public void check(
      Actor a, boolean valid, String object, String hotel, String order, String message) {
    if (!valid) {
      audit.denied(a, object, hotel, order, message);
      throw new BusinessException("FORBIDDEN", message, Map.of(), 403);
    }
  }

  /** Requires the actor to be the reception user responsible for the order. */
  public void responsible(Actor a, Map<String, Object> order) {
    check(
        a,
        a.is("RECEPTION") && a.id().equals(str(order, "ownerId")),
        str(order, "id"),
        str(order, "hotelId"),
        str(order, "id"),
        "只能处理自己负责的订单");
  }

  /** Requires the actor to manage the specified hotel. */
  public void hotel(Actor a, String hotel) {
    check(a, a.is("HOTEL") && a.hotelId().equals(hotel), hotel, hotel, null, "只能处理本酒店数据");
  }

  /** Checks order visibility against the actor's role and assignment. */
  public void readOrder(Actor a, Map<String, Object> order) {
    check(
        a,
        a.is("ADMIN")
            || (a.is("RECEPTION") && a.id().equals(str(order, "ownerId")))
            || (a.is("HOTEL") && a.hotelId().equals(str(order, "hotelId"))),
        str(order, "id"),
        str(order, "hotelId"),
        str(order, "id"),
        "订单不在可访问范围内");
  }

  /** Masks the middle of a phone number for limited-visibility views. */
  public static String maskPhone(String phone) {
    return phone.length() < 7
        ? "***"
        : phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
  }
}
