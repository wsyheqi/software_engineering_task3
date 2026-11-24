package cn.edu.campus.hospitality.shared;

import static cn.edu.campus.hospitality.shared.Data.map;
import static cn.edu.campus.hospitality.shared.Data.require;
import static cn.edu.campus.hospitality.shared.Data.str;

import cn.edu.campus.hospitality.audit.AuditService;
import cn.edu.campus.hospitality.identity.Access;
import cn.edu.campus.hospitality.identity.AccountLocks;
import cn.edu.campus.hospitality.identity.Actor;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Persists idempotent requests and processes them in database transactions. */
@Service
public class CommandService {
  private final Db db;
  private final Json json;
  private final Access access;
  private final AuditService audit;
  private final BusinessClock clock;
  private final TransactionTemplate tx;
  private final Map<String, CommandHandler> handlers = new HashMap<>();
  private final AccountLocks accountLocks;

  /** Registers handlers and configures the transaction used to process saved requests. */
  public CommandService(
      Db db,
      Json json,
      Access access,
      AuditService audit,
      BusinessClock clock,
      PlatformTransactionManager manager,
      List<CommandHandler> list,
      AccountLocks accountLocks) {
    this.db = db;
    this.json = json;
    this.access = access;
    this.audit = audit;
    this.clock = clock;
    this.tx = new TransactionTemplate(manager);
    this.accountLocks = accountLocks;
    this.tx.setIsolationLevel(
        org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
    for (var h : list) {
      for (String action : h.actions()) {
        if (handlers.put(action, h) != null) {
          throw new IllegalStateException("Duplicate command " + action);
        }
      }
    }
  }

  /** Submits or replays an operation identified by its idempotency key. */
  public Map<String, Object> submit(
      Actor actor, String action, Map<String, Object> body, String requestId) {
    require(
        requestId != null && requestId.matches("[A-Za-z0-9:_-]{8,100}"),
        "VALIDATION",
        "请提供稳定的 Idempotency-Key 请求标识（8～100 位）");
    require(handlers.containsKey(action), "VALIDATION", "未知操作");
    String hash = json.hash(body);
    try {
      db.insert(
          "operation_request",
          map(
              "id",
              requestId,
              "actor_id",
              actor.id(),
              "action_type",
              action,
              "payload_hash",
              hash,
              "payload_json",
              json.write(body),
              "status",
              "PENDING",
              "created_at",
              clock.now(),
              "updated_at",
              clock.now()));
    } catch (DuplicateKeyException duplicate) {
      /* A committed request owns this identity. */
    }
    var stored = db.one("SELECT * FROM operation_request WHERE id=?", requestId);
    access.check(
        actor, actor.id().equals(str(stored, "actorId")), requestId, null, null, "请求不在本人范围内");
    require(
        action.equals(str(stored, "actionType")) && hash.equals(str(stored, "payloadHash")),
        "REQUEST_PAYLOAD_CONFLICT",
        "同一请求标识不能用于不同操作或参数");
    return process(requestId);
  }

  /** Looks up the result of a request owned by the actor. */
  public Map<String, Object> query(Actor actor, String requestId) {
    var r = db.one("SELECT * FROM operation_request WHERE id=?", requestId);
    access.check(actor, actor.id().equals(str(r, "actorId")), requestId, null, null, "请求不在本人范围内");
    return result(r);
  }

  /** Locks and runs a pending command, preserving its terminal response for replay. */
  public Map<String, Object> process(String requestId) {
    try {
      return tx.execute(
          status -> {
            var r = db.one("SELECT * FROM operation_request WHERE id=? FOR UPDATE", requestId);
            if (!"PENDING".equals(str(r, "status"))) {
              return result(r);
            }
            Actor actor = accountLocks.active(access.byId(str(r, "actorId")));
            String action = str(r, "actionType");
            Map<String, Object> data;
            try {
              data = handlers.get(action).handle(actor, action, json.read(str(r, "payloadJson")));
            } catch (IllegalArgumentException
                | ArithmeticException
                | java.time.DateTimeException
                | ClassCastException invalid) {
              throw new BusinessException("VALIDATION", "输入格式无效，请检查日期、金额及数量", Map.of(), 400);
            }
            var response =
                map(
                    "requestId",
                    requestId,
                    "status",
                    "SUCCEEDED",
                    "businessCode",
                    str(data, "businessCode").isBlank() ? "OK" : data.get("businessCode"),
                    "message",
                    str(data, "message").isBlank() ? "操作成功" : data.get("message"),
                    "data",
                    data,
                    "httpStatus",
                    200);
            audit.record(
                actor,
                action,
                str(data, "objectId").isBlank() ? str(data, "orderId") : str(data, "objectId"),
                str(data, "hotelId"),
                str(data, "orderId"),
                requestId,
                "SUCCESS",
                "业务操作已提交");
            finish(requestId, "SUCCEEDED", response);
            return json.read(json.write(response));
          });
    } catch (BusinessException ex) {
      return tx.execute(
          status -> {
            var r = db.one("SELECT * FROM operation_request WHERE id=? FOR UPDATE", requestId);
            if (!"PENDING".equals(str(r, "status"))) {
              return result(r);
            }
            String state =
                ex.code().equals("NEEDS_RECONFIRMATION") ? "NEEDS_RECONFIRMATION" : "REJECTED";
            var response =
                map(
                    "requestId",
                    requestId,
                    "status",
                    state,
                    "businessCode",
                    ex.code(),
                    "message",
                    ex.getMessage(),
                    "data",
                    ex.data(),
                    "httpStatus",
                    ex.httpStatus());
            finish(requestId, state, response);
            var actor = Actor.from(db.one("SELECT * FROM app_user WHERE id=?", str(r, "actorId")));
            audit.record(
                actor,
                str(r, "actionType"),
                null,
                actor.hotelId(),
                null,
                requestId,
                state,
                ex.getMessage());
            return json.read(json.write(response));
          });
    } catch (TransientDataAccessException transientFailure) {
      // Lock contention is not proof of business failure. The durable request remains pending.
      return map(
          "requestId",
          requestId,
          "status",
          "PENDING",
          "businessCode",
          "PROCESSING",
          "message",
          "请求已保存，正在处理，请按原请求查询",
          "httpStatus",
          202);
    }
  }

  /** Retries pending commands using their original stored payloads. */
  public void recover() {
    for (var r :
        db.rows(
            "SELECT id FROM operation_request WHERE status='PENDING' AND created_at<? ORDER BY"
                + " created_at LIMIT 10",
            clock.now().minusSeconds(10))) {
      try {
        process(str(r, "id"));
      } catch (RuntimeException ignored) {
        /* Retain the request; never invent a failed commit. */
      }
    }
  }

  private void finish(String id, String status, Map<String, Object> response) {
    db.update(
        "UPDATE operation_request SET status=?,result_json=?,updated_at=? WHERE id=?",
        status,
        json.write(response),
        clock.now(),
        id);
  }

  private Map<String, Object> result(Map<String, Object> row) {
    return row.get("resultJson") == null
        ? map(
            "requestId",
            row.get("id"),
            "status",
            "PENDING",
            "businessCode",
            "PROCESSING",
            "message",
            "请求处理中，请稍后查询",
            "httpStatus",
            202)
        : json.read(str(row, "resultJson"));
  }
}
