package cn.edu.campus.hospitality.notification;

import static cn.edu.campus.hospitality.shared.Data.id;
import static cn.edu.campus.hospitality.shared.Data.integer;
import static cn.edu.campus.hospitality.shared.Data.map;
import static cn.edu.campus.hospitality.shared.Data.require;
import static cn.edu.campus.hospitality.shared.Data.required;
import static cn.edu.campus.hospitality.shared.Data.str;

import cn.edu.campus.hospitality.identity.Access;
import cn.edu.campus.hospitality.identity.Actor;
import cn.edu.campus.hospitality.shared.BusinessClock;
import cn.edu.campus.hospitality.shared.CommandHandler;
import cn.edu.campus.hospitality.shared.Data;
import cn.edu.campus.hospitality.shared.Db;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Tracks notification tasks and prepares the recipient's notice and work views. */
@Service
public class NotificationService implements CommandHandler {
  private final Db db;
  private final Access access;
  private final BusinessClock clock;
  private final JavaMailSender mail;
  private final boolean enabled;
  private final String from;
  private final TransactionTemplate tx;

  /** Creates the notification service with mail settings and a transaction manager. */
  public NotificationService(
      Db db,
      Access access,
      BusinessClock clock,
      JavaMailSender mail,
      @Value("${app.mail-enabled}") boolean enabled,
      @Value("${app.mail-from}") String from,
      PlatformTransactionManager manager) {
    this.db = db;
    this.access = access;
    this.clock = clock;
    this.mail = mail;
    this.enabled = enabled;
    this.from = from;
    this.tx = new TransactionTemplate(manager);
  }

  /** Adds a delivery task if its event, recipient, and channel are new. */
  public void enqueue(
      String event,
      String recipient,
      String channel,
      String order,
      String hotel,
      String title,
      String body) {
    if (db.count(
            "SELECT COUNT(*) FROM notification_task WHERE event_key=? AND recipient_id=? AND"
                + " channel=?",
            event,
            recipient,
            channel)
        > 0) {
      return;
    }
    db.insert(
        "notification_task",
        map(
            "id",
            id(),
            "event_key",
            event,
            "recipient_id",
            recipient,
            "channel",
            channel,
            "order_id",
            order,
            "hotel_id",
            hotel,
            "title",
            title,
            "body",
            body,
            "status",
            "PENDING",
            "attempts",
            0,
            "next_attempt_at",
            clock.now(),
            "created_at",
            clock.now()));
  }

  /** Queues in-app notice and optional mail for the reception user responsible for an order. */
  public void reception(
      String event, Map<String, Object> order, String title, String body, boolean email) {
    enqueue(
        event,
        str(order, "ownerId"),
        "IN_APP",
        str(order, "id"),
        str(order, "hotelId"),
        title,
        body);
    if (email) {
      enqueue(
          event,
          str(order, "ownerId"),
          "EMAIL",
          str(order, "id"),
          str(order, "hotelId"),
          title,
          body);
    }
  }

  /** Queues in-app notices for the enabled staff of an order's hotel. */
  public void hotel(String event, Map<String, Object> order, String title, String body) {
    for (var user :
        db.rows(
            "SELECT id FROM app_user WHERE role='HOTEL' AND enabled=TRUE AND hotel_id=?",
            order.get("hotelId"))) {
      enqueue(
          event, str(user, "id"), "IN_APP", str(order, "id"), str(order, "hotelId"), title, body);
    }
  }

  /** Lists recent in-app notices addressed to the actor. */
  public List<Map<String, Object>> notifications(Actor actor) {
    return db.rows(
        "SELECT * FROM notification WHERE recipient_id=? ORDER BY created_at DESC LIMIT 200",
        actor.id());
  }

  /** Returns actionable records and delivery failures visible to the actor. */
  public Map<String, Object> todos(Actor actor) {
    access.roles(actor, "ADMIN", "HOTEL", "RECEPTION");
    String clause = actor.is("ADMIN") ? "1=1" : actor.is("HOTEL") ? "o.hotel_id=?" : "o.owner_id=?";
    Object[] args =
        actor.is("ADMIN")
            ? new Object[] {}
            : new Object[] {actor.is("HOTEL") ? actor.hotelId() : actor.id()};
    var approvals =
        db.rows(
            "SELECT a.*,o.code AS order_code,o.hotel_id FROM special_change_approval a JOIN"
                + " booking_order o ON a.order_id=o.id WHERE "
                + clause
                + " AND o.status='CONFIRMED' AND (a.admin_id IS NULL OR a.hotel_user_id IS NULL)"
                + " ORDER BY a.created_at DESC",
            args);
    var extensions =
        db.rows(
            "SELECT e.*,o.code AS order_code,o.hotel_id FROM extension_request e JOIN booking_order"
                + " o ON e.order_id=o.id WHERE "
                + clause
                + " AND e.status='PENDING' ORDER BY e.created_at DESC",
            args);
    var fees =
        db.rows(
            "SELECT f.*,o.code AS order_code,o.hotel_id FROM fee_record f JOIN booking_order o ON"
                + " f.order_id=o.id WHERE "
                + clause
                + " AND f.status IN ('PENDING','DISPUTED') ORDER BY f.created_at DESC",
            args);
    List<Map<String, Object>> work =
        actor.is("ADMIN")
            ? db.rows("SELECT * FROM work_item WHERE status='OPEN' ORDER BY created_at DESC")
            : actor.is("HOTEL")
                ? db.rows(
                    "SELECT * FROM work_item WHERE status='OPEN' AND hotel_id=? AND"
                        + " kind='INVENTORY_CONFLICT' ORDER BY created_at DESC",
                    actor.hotelId())
                : List.of();
    var failed =
        actor.is("ADMIN")
            ? db.rows(
                "SELECT"
                    + " id,recipient_id,channel,order_id,title,status,attempts,"
                    + "last_error,next_attempt_at"
                    + " FROM notification_task WHERE status IN ('RETRY','FAILED') ORDER BY"
                    + " created_at DESC")
            : List.of();
    var relocations =
        db.rows(
            "SELECT c.*,o.code AS order_code,o.hotel_id FROM relocation_case c JOIN booking_order o"
                + " ON c.order_id=o.id WHERE "
                + clause
                + " AND c.status='OPEN'",
            args);
    return map(
        "approvals",
        approvals,
        "extensions",
        extensions,
        "fees",
        fees,
        "relocations",
        relocations,
        "workItems",
        work,
        "failedTasks",
        failed);
  }

  /** Claims ready tasks and records the outcome of each delivery attempt. */
  public void deliver() {
    // A lease makes a process crash recoverable; SMTP is deliberately outside a DB transaction.
    var ready =
        db.rows(
            "SELECT id FROM notification_task WHERE (status IN ('PENDING','RETRY') OR"
                + " status='SENDING') AND next_attempt_at<=? ORDER BY created_at LIMIT 20",
            clock.now());
    for (var candidate : ready) {
      var task =
          tx.execute(
              s -> {
                var t =
                    db.one(
                        "SELECT * FROM notification_task WHERE id=? FOR UPDATE",
                        candidate.get("id"));
                if (!Set.of("PENDING", "RETRY", "SENDING").contains(str(t, "status"))
                    || Data.time(t, "nextAttemptAt").isAfter(clock.now())) {
                  return null;
                }
                db.update(
                    "UPDATE notification_task SET"
                        + " status='SENDING',attempts=attempts+1,next_attempt_at=? WHERE id=?",
                    clock.now().plusMinutes(2),
                    t.get("id"));
                return t;
              });
      if (task == null) {
        continue;
      }
      try {
        if ("EMAIL".equals(str(task, "channel"))) {
          if (!enabled) {
            throw new IllegalStateException("学校邮箱尚未配置，等待管理员处理");
          }
          var recipient = db.one("SELECT email FROM app_user WHERE id=?", task.get("recipientId"));
          require(!str(recipient, "email").isBlank(), "MAIL_ADDRESS_MISSING", "责任人员未配置学校邮箱");
          SimpleMailMessage message = new SimpleMailMessage();
          message.setFrom(from);
          message.setTo(str(recipient, "email"));
          message.setSubject(str(task, "title"));
          message.setText(str(task, "body"));
          mail.send(message);
        }
        tx.executeWithoutResult(
            s -> {
              if ("IN_APP".equals(str(task, "channel"))
                  && db.count("SELECT COUNT(*) FROM notification WHERE task_id=?", task.get("id"))
                      == 0) {
                db.insert(
                    "notification",
                    map(
                        "id",
                        id(),
                        "task_id",
                        task.get("id"),
                        "recipient_id",
                        task.get("recipientId"),
                        "order_id",
                        task.get("orderId"),
                        "title",
                        task.get("title"),
                        "body",
                        task.get("body"),
                        "is_read",
                        false,
                        "created_at",
                        clock.now()));
              }
              db.update(
                  "UPDATE notification_task SET status='SENT',last_error=NULL WHERE id=?",
                  task.get("id"));
              db.update(
                  "UPDATE work_item SET status='RESOLVED' WHERE source_key=?",
                  "mail:" + task.get("id"));
            });
      } catch (Exception ex) {
        tx.executeWithoutResult(
            s -> {
              int attempt = integer(task, "attempts") + 1;
              String error = enabled ? "投递失败，请检查邮箱地址、SMTP 配置和学校邮件服务" : "学校邮箱尚未配置，等待管理员处理";
              db.update(
                  "UPDATE notification_task SET status=?,last_error=?,next_attempt_at=? WHERE id=?",
                  attempt >= 5 ? "FAILED" : "RETRY",
                  error,
                  clock.now().plusMinutes(Math.min(60, 1L << attempt)),
                  task.get("id"));
              if (db.count(
                      "SELECT COUNT(*) FROM work_item WHERE source_key=?", "mail:" + task.get("id"))
                  == 0) {
                db.insert(
                    "work_item",
                    map(
                        "id",
                        id(),
                        "source_key",
                        "mail:" + task.get("id"),
                        "kind",
                        "NOTIFICATION_FAILURE",
                        "hotel_id",
                        task.get("hotelId"),
                        "order_id",
                        task.get("orderId"),
                        "title",
                        "通知发送失败",
                        "detail",
                        error,
                        "status",
                        "OPEN",
                        "created_at",
                        clock.now()));
              } else {
                db.update(
                    "UPDATE work_item SET status='OPEN',detail=? WHERE source_key=?",
                    error,
                    "mail:" + task.get("id"));
              }
            });
      }
    }
  }

  @Override
  public Set<String> actions() {
    return Set.of("NOTIFICATION_READ", "TASK_RETRY", "WORK_RESOLVE");
  }

  @Override
  public Map<String, Object> handle(Actor actor, String action, Map<String, Object> body) {
    String target = required(body, "id");
    if (action.equals("NOTIFICATION_READ")) {
      var n = db.one("SELECT * FROM notification WHERE id=?", target);
      access.check(
          actor, actor.id().equals(str(n, "recipientId")), target, null, null, "通知不在本人范围内");
      db.update("UPDATE notification SET is_read=TRUE WHERE id=?", target);
    } else {
      access.roles(actor, "ADMIN");
      if (action.equals("TASK_RETRY")) {
        db.one("SELECT * FROM notification_task WHERE id=?", target);
        db.update(
            "UPDATE notification_task SET status='PENDING',attempts=0,next_attempt_at=? WHERE id=?",
            clock.now(),
            target);
      } else {
        required(body, "note");
        db.update(
            "UPDATE work_item SET status='MANUAL_RESOLVED',detail=? WHERE id=?",
            required(body, "note"),
            target);
      }
    }
    return map("message", "处理结果已保存");
  }
}
