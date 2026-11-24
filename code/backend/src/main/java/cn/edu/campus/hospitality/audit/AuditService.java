package cn.edu.campus.hospitality.audit;

import static cn.edu.campus.hospitality.shared.Data.id;
import static cn.edu.campus.hospitality.shared.Data.map;

import cn.edu.campus.hospitality.identity.Actor;
import cn.edu.campus.hospitality.shared.BusinessClock;
import cn.edu.campus.hospitality.shared.BusinessException;
import cn.edu.campus.hospitality.shared.Db;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Records security-relevant operations and lets staff review their permitted audit trail. */
@Service
public class AuditService {
  private final Db db;
  private final BusinessClock clock;

  /** Creates the audit service with the business clock for entry timestamps. */
  public AuditService(Db db, BusinessClock clock) {
    this.db = db;
    this.clock = clock;
  }

  /** Persists an operation result with its actor, business object, and request identifiers. */
  public void record(
      Actor actor,
      String action,
      String object,
      String hotel,
      String order,
      String request,
      String outcome,
      String detail) {
    db.insert(
        "audit_log",
        map(
            "id",
            id(),
            "actor_id",
            actor == null ? null : actor.id(),
            "actor_name",
            actor == null ? "未登录" : actor.name(),
            "actor_role",
            actor == null ? "ANONYMOUS" : actor.role(),
            "hotel_id",
            hotel,
            "order_id",
            order,
            "request_id",
            request,
            "action_type",
            action,
            "object_id",
            object,
            "outcome",
            outcome,
            "detail",
            detail.replaceAll("(?<!\\d)1\\d{7}(\\d{3})(?!\\d)", "1*******$1"),
            "created_at",
            clock.now()));
  }

  /** Records an access denial with the affected object, hotel, and order identifiers. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void denied(Actor actor, String object, String hotel, String order, String message) {
    record(actor, "ACCESS_DENIED", object, hotel, order, null, "DENIED", message);
  }

  /** Records a failed request independently of its rolled-back operation. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void failure(Actor actor, String request, String action, String message) {
    record(actor, action, null, actor.hotelId(), null, request, "REJECTED", message);
  }

  /** Returns audit entries visible to the actor within the requested object and date filters. */
  public List<Map<String, Object>> list(Actor actor, String object, String from, String to) {
    if (!actor.is("ADMIN") && !actor.is("HOTEL")) {
      denied(actor, "AUDIT", null, null, "无日志查询权限");
      throw new BusinessException("FORBIDDEN", "无日志查询权限", Map.of(), 403);
    }
    String sql = "SELECT * FROM audit_log WHERE 1=1";
    List<Object> args = new ArrayList<>();
    if (actor.is("HOTEL")) {
      sql += " AND hotel_id=?";
      args.add(actor.hotelId());
    }
    if (object != null && !object.isBlank()) {
      sql += " AND (object_id=? OR order_id=? OR request_id=?)";
      args.addAll(List.of(object, object, object));
    }
    if (from != null && !from.isBlank()) {
      sql += " AND created_at>=?";
      args.add(java.time.LocalDate.parse(from).atStartOfDay());
    }
    if (to != null && !to.isBlank()) {
      sql += " AND created_at<?";
      args.add(java.time.LocalDate.parse(to).plusDays(1).atStartOfDay());
    }
    return db.rows(sql + " ORDER BY created_at DESC LIMIT 300", args.toArray());
  }
}
