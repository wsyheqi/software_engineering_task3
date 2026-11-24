package cn.edu.campus.hospitality.booking;

import static cn.edu.campus.hospitality.shared.Data.bool;
import static cn.edu.campus.hospitality.shared.Data.date;
import static cn.edu.campus.hospitality.shared.Data.decimal;
import static cn.edu.campus.hospitality.shared.Data.id;
import static cn.edu.campus.hospitality.shared.Data.integer;
import static cn.edu.campus.hospitality.shared.Data.map;
import static cn.edu.campus.hospitality.shared.Data.number;
import static cn.edu.campus.hospitality.shared.Data.object;
import static cn.edu.campus.hospitality.shared.Data.objects;
import static cn.edu.campus.hospitality.shared.Data.require;
import static cn.edu.campus.hospitality.shared.Data.required;
import static cn.edu.campus.hospitality.shared.Data.state;
import static cn.edu.campus.hospitality.shared.Data.str;
import static cn.edu.campus.hospitality.shared.Data.time;

import cn.edu.campus.hospitality.booking.domain.OrderState;
import cn.edu.campus.hospitality.identity.Access;
import cn.edu.campus.hospitality.identity.Actor;
import cn.edu.campus.hospitality.inventory.InventoryService;
import cn.edu.campus.hospitality.notification.NotificationService;
import cn.edu.campus.hospitality.search.Plan;
import cn.edu.campus.hospitality.search.SearchService;
import cn.edu.campus.hospitality.shared.BusinessClock;
import cn.edu.campus.hospitality.shared.BusinessException;
import cn.edu.campus.hospitality.shared.CommandHandler;
import cn.edu.campus.hospitality.shared.Db;
import cn.edu.campus.hospitality.shared.Json;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/** Manages booking commands and the order and inventory changes they require. */
@Service
public class BookingService implements CommandHandler {
  private final Db db;
  private final Json json;
  private final Access access;
  private final InventoryService inventory;
  private final SearchService search;
  private final BusinessClock clock;
  private final NotificationService notice;

  /** Creates the booking handler with access checks, inventory, and quote services. */
  public BookingService(
      Db db,
      Json json,
      Access access,
      InventoryService inventory,
      SearchService search,
      BusinessClock clock,
      NotificationService notice) {
    this.db = db;
    this.json = json;
    this.access = access;
    this.inventory = inventory;
    this.search = search;
    this.clock = clock;
    this.notice = notice;
  }

  @Override
  public Set<String> actions() {
    return Set.of(
        "BOOK",
        "CHANGE",
        "CANCEL",
        "SPECIAL_REQUEST",
        "SPECIAL_CONFIRM",
        "INVENTORY_UPDATE",
        "INVENTORY_BLOCK");
  }

  @Override
  public Map<String, Object> handle(Actor actor, String action, Map<String, Object> body) {
    return switch (action) {
      case "BOOK" -> book(actor, body);
      case "CHANGE" -> change(actor, body);
      case "CANCEL" -> cancel(actor, body);
      case "SPECIAL_REQUEST" -> specialRequest(actor, body);
      case "SPECIAL_CONFIRM" -> specialConfirm(actor, body);
      case "INVENTORY_UPDATE" -> inventory.update(actor, body);
      case "INVENTORY_BLOCK" -> inventory.block(actor, body);
      default -> throw new IllegalArgumentException(action);
    };
  }

  /** Locks related hotels before locking the order, then verifies its hotel assignment. */
  public Map<String, Object> lockOrder(String orderId, Collection<String> otherHotels) {
    var before = db.one("SELECT * FROM booking_order WHERE id=?", orderId);
    List<String> hotels = new ArrayList<>(otherHotels);
    hotels.add(str(before, "hotelId"));
    inventory.lockHotels(hotels, false);
    var order = db.one("SELECT * FROM booking_order WHERE id=? FOR UPDATE", orderId);
    require(
        str(before, "hotelId").equals(str(order, "hotelId")), "VERSION_CONFLICT", "订单归属已变化，请刷新");
    return order;
  }

  /** Validates the requested order transition and persists the new status and version. */
  public void transition(Map<String, Object> order, String state) {
    OrderState.valueOf(str(order, "status")).transitionTo(OrderState.valueOf(state));
    db.update(
        "UPDATE booking_order SET status=?,version=version+1 WHERE id=?", state, order.get("id"));
    order.put("status", state);
  }

  /** Stores the current order and room lines as an audit revision for a mutation. */
  public void revision(String order, String action) {
    db.insert(
        "order_revision",
        map(
            "id",
            id(),
            "order_id",
            order,
            "action_type",
            action,
            "snapshot_json",
            json.write(
                map(
                    "order",
                    db.one("SELECT * FROM booking_order WHERE id=?", order),
                    "items",
                    db.rows("SELECT * FROM order_item WHERE order_id=?", order),
                    "nights",
                    db.rows("SELECT * FROM order_night WHERE order_id=?", order))),
            "created_at",
            clock.now()));
  }

  private Map<String, Object> book(Actor actor, Map<String, Object> body) {
    access.roles(actor, "RECEPTION");
    require(bool(body, "rulesConfirmed"), "CONFIRMATION_REQUIRED", "请阅读并确认取消期限及适用费用规则");
    var quote = ownQuote(actor, required(body, "quoteId"), "INITIAL", null);
    inventory.lockHotels(List.of(str(quote, "hotelId")), false);
    var snapshot = search.revalidate(quote);
    var plan = Plan.from(object(snapshot, "plan"));
    search.validateGuests(actor, plan);
    require(!plan.start().isBefore(clock.today()), "DATE_CONFLICT", "不能新订过去日期");
    String order = createConfirmed(actor.id(), str(quote, "demandId"), snapshot, null, true);
    return map(
        "businessCode",
        "BOOKED",
        "message",
        "预订成功，订单已自动确认",
        "orderId",
        order,
        "hotelId",
        quote.get("hotelId"));
  }

  /** Creates a confirmed order from a quote and optionally applies its inventory allocation. */
  public String createConfirmed(
      String owner, String demand, Map<String, Object> snapshot, String source, boolean allocate) {
    Plan p = Plan.from(object(snapshot, "plan"));
    List<Map<String, Object>> lines = objects(snapshot, "lines");
    if (allocate) {
      inventory.applyDeltas(lineDeltas(lines, 1));
    }
    String order = id();
    db.insert(
        "booking_order",
        map(
            "id",
            order,
            "code",
            "CR"
                + clock.today().toString().replace("-", "")
                + order.substring(0, 8).toUpperCase(Locale.ROOT),
            "owner_id",
            owner,
            "hotel_id",
            snapshot.get("hotelId"),
            "demand_id",
            demand == null || demand.isBlank() ? null : demand,
            "start_date",
            p.start(),
            "end_date",
            p.end(),
            "status",
            "PENDING",
            "total",
            snapshot.get("total"),
            "plan_json",
            json.write(p.view()),
            "rule_json",
            json.write(snapshot.get("rule")),
            "version",
            1,
            "source_order_id",
            source,
            "created_at",
            clock.now()));
    appendLines(order, lines);
    for (String guest : p.guestIds()) {
      db.insert("order_guest", map("order_id", order, "guest_id", guest));
    }
    transition(map("id", order, "status", "PENDING"), "CONFIRMED");
    return order;
  }

  /** Adds quoted nightly lines, creating one room item per room type as needed. */
  public void appendLines(String order, List<Map<String, Object>> lines) {
    Map<String, String> items = new HashMap<>();
    for (var item : db.rows("SELECT * FROM order_item WHERE order_id=?", order)) {
      items.put(str(item, "roomTypeId"), str(item, "id"));
    }
    for (var line : lines) {
      String room = str(line, "roomTypeId");
      String item = items.get(room);
      if (item == null) {
        item = id();
        items.put(room, item);
        db.insert(
            "order_item",
            map(
                "id",
                item,
                "order_id",
                order,
                "room_type_id",
                room,
                "code",
                line.get("code"),
                "name",
                line.get("roomName"),
                "quantity",
                line.get("quantity")));
      }
      db.insert(
          "order_night",
          map(
              "id",
              id(),
              "order_id",
              order,
              "item_id",
              item,
              "inventory_id",
              line.get("inventoryId"),
              "stay_date",
              date(line, "date"),
              "quantity",
              line.get("quantity"),
              "price",
              line.get("price"),
              "released",
              false,
              "plan_active",
              true));
    }
  }

  /** Combines quoted quantities into signed inventory changes keyed by nightly record. */
  public Map<String, Integer> lineDeltas(List<Map<String, Object>> lines, int direction) {
    Map<String, Integer> result = new LinkedHashMap<>();
    for (var line : lines) {
      result.merge(str(line, "inventoryId"), direction * integer(line, "quantity"), Integer::sum);
    }
    return result;
  }

  /** Checks that the actor owns the quote and that its mode and order match the operation. */
  public Map<String, Object> ownQuote(Actor actor, String id, String mode, String orderId) {
    var q = search.quote(id);
    access.check(actor, actor.id().equals(str(q, "actorId")), id, null, null, "方案不属于当前用户");
    require(
        mode.equals(str(q, "mode")) && (orderId == null || orderId.equals(str(q, "orderId"))),
        "QUOTE_CONFLICT",
        "方案与本次操作不匹配");
    return q;
  }

  private Map<String, Object> change(Actor actor, Map<String, Object> body) {
    String orderId = required(body, "orderId");
    var q = ownQuote(actor, required(body, "quoteId"), "CHANGE", orderId);
    var before = db.one("SELECT * FROM booking_order WHERE id=?", orderId);
    access.responsible(actor, before);
    var order = lockOrder(orderId, List.of(str(q, "hotelId")));
    access.responsible(actor, order);
    state(order, "CONFIRMED");
    require(
        number(order, "version") == number(q, "orderVersion"),
        "VERSION_CONFLICT",
        "订单已变化，请重新取得改订方案");
    require(bool(body, "differenceConfirmed"), "CONFIRMATION_REQUIRED", "请先确认新整单价格及差额");
    if (!clock.now().isBefore(clock.cutoff(date(order, "startDate")))) {
      var approval =
          db.find(
                  "SELECT * FROM special_change_approval WHERE id=? AND order_id=?",
                  str(body, "approvalId"),
                  orderId)
              .orElseThrow(
                  () ->
                      new BusinessException("SPECIAL_APPROVAL_REQUIRED", "截止后改订须管理员及原酒店共同确认具体方案"));
      require(
          approval.get("adminId") != null
              && approval.get("hotelUserId") != null
              && str(approval, "snapshotHash").equals(str(q, "snapshotHash"))
              && str(approval, "quoteId").equals(str(q, "id")),
          "SPECIAL_APPROVAL_REQUIRED",
          "缺少双方对本方案的有效批准");
    }
    var snapshot = search.revalidate(q);
    Plan plan = Plan.from(object(snapshot, "plan"));
    var lines = objects(snapshot, "lines");
    search.validateGuests(actor, plan);
    revision(orderId, "CHANGE");
    Map<String, Integer> deltas = lineDeltas(lines, 1);
    inventory.held(orderId).forEach((k, v) -> deltas.merge(k, -v, Integer::sum));
    inventory.applyDeltas(deltas);
    db.update("DELETE FROM order_night WHERE order_id=?", orderId);
    db.update("DELETE FROM order_item WHERE order_id=?", orderId);
    db.update("DELETE FROM order_guest WHERE order_id=?", orderId);
    appendLines(orderId, lines);
    for (String guest : plan.guestIds()) {
      db.insert("order_guest", map("order_id", orderId, "guest_id", guest));
    }
    db.update(
        "UPDATE booking_order SET"
            + " hotel_id=?,start_date=?,end_date=?,total=?,plan_json=?,rule_json=?,"
            + "version=version+1"
            + " WHERE id=?",
        snapshot.get("hotelId"),
        plan.start(),
        plan.end(),
        snapshot.get("total"),
        json.write(plan.view()),
        json.write(snapshot.get("rule")),
        orderId);
    return map(
        "businessCode",
        "CHANGED",
        "message",
        "改订已生效",
        "orderId",
        orderId,
        "hotelId",
        snapshot.get("hotelId"),
        "difference",
        decimal(snapshot, "total").subtract(decimal(order, "total")));
  }

  private Map<String, Object> cancel(Actor actor, Map<String, Object> body) {
    String orderId = required(body, "orderId");
    access.responsible(actor, db.one("SELECT * FROM booking_order WHERE id=?", orderId));
    var order = lockOrder(orderId, List.of());
    access.responsible(actor, order);
    state(order, "CONFIRMED");
    BigDecimal fee = BigDecimal.ZERO;
    var rule = json.read(str(order, "ruleJson"));
    if (!clock.now().isBefore(time(rule, "cancelDeadline"))) {
      for (var night :
          db.rows(
              "SELECT * FROM order_night WHERE order_id=? AND stay_date=?",
              orderId,
              date(order, "startDate"))) {
        fee =
            fee.add(
                decimal(night, "price").multiply(BigDecimal.valueOf(integer(night, "quantity"))));
      }
    }
    revision(orderId, "CANCEL");
    inventory.release(orderId, LocalDate.of(1900, 1, 1), true);
    transition(order, "CANCELLED");
    fee(
        orderId,
        "CANCELLATION",
        fee,
        "cancel:" + orderId,
        "CONFIRMED",
        "整单取消，按订单确认时的规则与锁定首晚价格计算",
        actor.id());
    return map(
        "businessCode",
        "CANCELLED",
        "message",
        "订单已取消，名额已释放",
        "orderId",
        orderId,
        "hotelId",
        order.get("hotelId"),
        "cancellationFee",
        fee);
  }

  /** Records a fee once for a given business source key. */
  public void fee(
      String order,
      String category,
      BigDecimal amount,
      String source,
      String status,
      String note,
      String actor) {
    if (db.count("SELECT COUNT(*) FROM fee_record WHERE source_key=?", source) > 0) {
      return;
    }
    db.insert(
        "fee_record",
        map(
            "id",
            id(),
            "order_id",
            order,
            "category",
            category,
            "amount",
            amount,
            "source_key",
            source,
            "status",
            status,
            "note",
            note,
            "proposed_by",
            actor,
            "created_at",
            clock.now()));
  }

  private Map<String, Object> specialRequest(Actor actor, Map<String, Object> body) {
    String orderId = required(body, "orderId");
    var q = ownQuote(actor, required(body, "quoteId"), "CHANGE", orderId);
    access.responsible(actor, db.one("SELECT * FROM booking_order WHERE id=?", orderId));
    var order = lockOrder(orderId, List.of(str(q, "hotelId")));
    state(order, "CONFIRMED");
    require(
        number(order, "version") == number(q, "orderVersion"), "VERSION_CONFLICT", "订单已变化，请刷新方案");
    search.revalidate(q);
    var existing =
        db.find(
            "SELECT * FROM special_change_approval WHERE order_id=? AND quote_id=?",
            orderId,
            q.get("id"));
    if (existing.isPresent()) {
      return map(
          "approvalId", existing.get().get("id"), "orderId", orderId, "message", "本方案已提交双方确认");
    }
    String approval = id();
    db.insert(
        "special_change_approval",
        map(
            "id",
            approval,
            "order_id",
            orderId,
            "quote_id",
            q.get("id"),
            "snapshot_hash",
            q.get("snapshotHash"),
            "note",
            str(body, "note"),
            "created_at",
            clock.now()));
    for (var admin : db.rows("SELECT id FROM app_user WHERE role='ADMIN' AND enabled=TRUE")) {
      notice.enqueue(
          "approval:" + approval,
          str(admin, "id"),
          "IN_APP",
          orderId,
          str(order, "hotelId"),
          "特殊改订待确认",
          "订单 " + order.get("code") + " 请求截止后的特殊改订，请查看具体方案。");
    }
    notice.hotel(
        "approval:" + approval, order, "特殊改订待确认", "请确认订单 " + order.get("code") + " 的指定改订方案。");
    return map(
        "approvalId",
        approval,
        "orderId",
        orderId,
        "hotelId",
        order.get("hotelId"),
        "message",
        "已提交管理员及酒店共同确认");
  }

  private Map<String, Object> specialConfirm(Actor actor, Map<String, Object> body) {
    access.roles(actor, "ADMIN", "HOTEL");
    var initial = db.one("SELECT * FROM special_change_approval WHERE id=?", required(body, "id"));
    var quote = search.quote(str(initial, "quoteId"));
    var order = lockOrder(str(initial, "orderId"), List.of(str(quote, "hotelId")));
    state(order, "CONFIRMED");
    if (actor.is("HOTEL")) {
      access.hotel(actor, str(order, "hotelId"));
    }
    require(
        number(order, "version") == number(quote, "orderVersion"),
        "VERSION_CONFLICT",
        "订单已变化，不能批准旧方案");
    search.revalidate(quote);
    var approval =
        db.one("SELECT * FROM special_change_approval WHERE id=? FOR UPDATE", initial.get("id"));
    String prefix = actor.is("ADMIN") ? "admin" : "hotel_user";
    String opinion = str(body, "note").isBlank() ? "已核对并同意本指定方案" : str(body, "note");
    require(opinion.length() <= 500, "VALIDATION", "确认意见过长");
    if (actor.is("ADMIN")) {
      db.update(
          "UPDATE special_change_approval SET admin_id=?,admin_at=?,admin_note=? WHERE id=? AND"
              + " admin_id IS NULL",
          actor.id(),
          clock.now(),
          opinion,
          approval.get("id"));
    } else {
      db.update(
          "UPDATE special_change_approval SET hotel_user_id=?,hotel_at=?,hotel_note=? WHERE id=?"
              + " AND hotel_user_id IS NULL",
          actor.id(),
          clock.now(),
          opinion,
          approval.get("id"));
    }
    return map(
        "approvalId",
        approval.get("id"),
        "orderId",
        order.get("id"),
        "hotelId",
        order.get("hotelId"),
        "message",
        "本方确认已保存；双方齐备后由接待人员确认差额并执行");
  }

  /** Lists orders within the actor's reception, hotel, or administrative scope. */
  public List<Map<String, Object>> list(Actor actor) {
    access.roles(actor, "ADMIN", "HOTEL", "RECEPTION");
    String where =
        actor.is("ADMIN") ? "" : actor.is("HOTEL") ? " WHERE o.hotel_id=?" : " WHERE o.owner_id=?";
    Object[] args =
        actor.is("ADMIN")
            ? new Object[] {}
            : new Object[] {actor.is("HOTEL") ? actor.hotelId() : actor.id()};
    return db
        .rows(
            "SELECT o.*,h.name AS hotel_name,u.display_name AS owner_name FROM booking_order o JOIN"
                + " hotel h ON o.hotel_id=h.id JOIN app_user u ON o.owner_id=u.id"
                + where
                + " ORDER BY o.created_at DESC LIMIT 300",
            args)
        .stream()
        .map(
            row -> {
              row.remove("planJson");
              row.remove("ruleJson");
              return row;
            })
        .toList();
  }

  /** Returns the permitted order detail and applies the actor's contact visibility rules. */
  public Map<String, Object> detail(Actor actor, String orderId) {
    var order =
        db.one(
            "SELECT o.*,h.name AS hotel_name,h.address AS hotel_address,u.display_name AS"
                + " owner_name FROM booking_order o JOIN hotel h ON o.hotel_id=h.id JOIN app_user u"
                + " ON u.id=o.owner_id WHERE o.id=?",
            orderId);
    access.readOrder(actor, order);
    var guests =
        db.rows(
            "SELECT g.id,g.name,g.phone,c.arrived_at,c.departed_at FROM order_guest x JOIN guest g"
                + " ON g.id=x.guest_id LEFT JOIN guest_checkin c ON c.order_id=x.order_id AND"
                + " c.guest_id=x.guest_id WHERE x.order_id=? ORDER BY g.id",
            orderId);
    if (actor.is("ADMIN")) {
      for (var g : guests) {
        g.put("phone", Access.maskPhone(str(g, "phone")));
      }
    }
    var plan = json.read(str(order, "planJson"));
    var rule = json.read(str(order, "ruleJson"));
    order.remove("planJson");
    order.remove("ruleJson");
    return map(
        "order",
        order,
        "plan",
        plan,
        "rule",
        rule,
        "guests",
        guests,
        "items",
        db.rows("SELECT * FROM order_item WHERE order_id=?", orderId),
        "nights",
        db.rows(
            "SELECT n.*,i.name AS room_name FROM order_night n JOIN order_item i ON n.item_id=i.id"
                + " WHERE n.order_id=? ORDER BY n.stay_date,i.code",
            orderId),
        "actualNights",
        db.rows("SELECT * FROM actual_room_night WHERE order_id=? ORDER BY stay_date", orderId),
        "fees",
        db.rows("SELECT * FROM fee_record WHERE order_id=? ORDER BY created_at", orderId),
        "approvals",
        db.rows(
            "SELECT * FROM special_change_approval WHERE order_id=? ORDER BY created_at DESC",
            orderId),
        "extensions",
        db.rows(
            "SELECT * FROM extension_request WHERE order_id=? ORDER BY created_at DESC", orderId),
        "relocations",
        db.rows(
            "SELECT * FROM relocation_case WHERE order_id=? OR new_order_id=?", orderId, orderId));
  }

  /** Returns the stays belonging to the signed-in guest with limited contact fields. */
  public List<Map<String, Object>> guestStays(Actor actor) {
    access.roles(actor, "GUEST");
    return db
        .rows(
            "SELECT o.id,o.code,o.start_date,o.end_date,o.status,h.name AS hotel_name,h.address AS"
                + " hotel_address,c.arrived_at,c.departed_at FROM booking_order o JOIN order_guest"
                + " x ON x.order_id=o.id JOIN hotel h ON o.hotel_id=h.id LEFT JOIN guest_checkin c"
                + " ON c.order_id=o.id AND c.guest_id=x.guest_id WHERE x.guest_id=? ORDER BY"
                + " o.created_at DESC",
            actor.guestId())
        .stream()
        .map(
            o -> {
              var g = db.one("SELECT name,phone FROM guest WHERE id=?", actor.guestId());
              o.put("guestName", g.get("name"));
              o.put("phone", Access.maskPhone(str(g, "phone")));
              o.put(
                  "items",
                  db.rows("SELECT name,quantity FROM order_item WHERE order_id=?", o.get("id")));
              return o;
            })
        .toList();
  }
}
