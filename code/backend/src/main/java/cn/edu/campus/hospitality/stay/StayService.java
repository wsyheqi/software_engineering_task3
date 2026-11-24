package cn.edu.campus.hospitality.stay;

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

import cn.edu.campus.hospitality.booking.BookingService;
import cn.edu.campus.hospitality.identity.Access;
import cn.edu.campus.hospitality.identity.Actor;
import cn.edu.campus.hospitality.inventory.InventoryService;
import cn.edu.campus.hospitality.notification.NotificationService;
import cn.edu.campus.hospitality.search.Plan;
import cn.edu.campus.hospitality.search.SearchService;
import cn.edu.campus.hospitality.shared.BusinessClock;
import cn.edu.campus.hospitality.shared.CommandHandler;
import cn.edu.campus.hospitality.shared.Db;
import cn.edu.campus.hospitality.shared.Json;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/** Handles check-in, checkout, extensions, and other lodging activity. */
@Service
public class StayService implements CommandHandler {
  private final Db db;
  private final Json json;
  private final Access access;
  private final BookingService booking;
  private final SearchService search;
  private final InventoryService inventory;
  private final NotificationService notice;
  private final BusinessClock clock;

  /** Creates the stay handler with order, inventory, and notice services. */
  public StayService(
      Db db,
      Json json,
      Access access,
      BookingService booking,
      SearchService search,
      InventoryService inventory,
      NotificationService notice,
      BusinessClock clock) {
    this.db = db;
    this.json = json;
    this.access = access;
    this.booking = booking;
    this.search = search;
    this.inventory = inventory;
    this.notice = notice;
    this.clock = clock;
  }

  @Override
  public Set<String> actions() {
    return Set.of(
        "CHECK_IN",
        "ACTUAL_NIGHT",
        "CHECK_OUT",
        "LATE_HOLD",
        "NO_SHOW",
        "EXTENSION_PROPOSE",
        "EXTENSION_CONFIRM",
        "FEE_PROPOSE",
        "FEE_REVIEW",
        "FEE_RESOLVE");
  }

  @Override
  public Map<String, Object> handle(Actor actor, String action, Map<String, Object> body) {
    return switch (action) {
      case "CHECK_IN" -> checkin(actor, body);
      case "ACTUAL_NIGHT" -> actual(actor, body);
      case "CHECK_OUT" -> checkout(actor, body);
      case "LATE_HOLD" -> late(actor, body);
      case "NO_SHOW" -> noShow(actor, body);
      case "EXTENSION_PROPOSE" -> proposeExtension(actor, body);
      case "EXTENSION_CONFIRM" -> confirmExtension(actor, body);
      case "FEE_PROPOSE" -> proposeFee(actor, body);
      case "FEE_REVIEW", "FEE_RESOLVE" -> reviewFee(actor, body, action.equals("FEE_RESOLVE"));
      default -> throw new IllegalArgumentException(action);
    };
  }

  private Map<String, Object> hotelOrder(Actor actor, String id) {
    var before = db.one("SELECT * FROM booking_order WHERE id=?", id);
    access.hotel(actor, str(before, "hotelId"));
    var order = booking.lockOrder(id, List.of());
    access.hotel(actor, str(order, "hotelId"));
    return order;
  }

  private Map<String, Object> result(Map<String, Object> order, String message) {
    return map("orderId", order.get("id"), "hotelId", order.get("hotelId"), "message", message);
  }

  private Map<String, Object> checkin(Actor actor, Map<String, Object> body) {
    var order = hotelOrder(actor, required(body, "orderId"));
    state(order, "CONFIRMED", "CHECKED_IN");
    String guest = required(body, "guestId");
    require(
        db.count(
                "SELECT COUNT(*) FROM order_guest WHERE order_id=? AND guest_id=?",
                order.get("id"),
                guest)
            == 1,
        "GUEST_CONFLICT",
        "只能登记本单来宾");
    require(!clock.today().isBefore(date(order, "startDate")), "DATE_CONFLICT", "尚未到计划入住日");
    require(
        clock.today().isBefore(date(order, "endDate")),
        "DATE_CONFLICT",
        "已到或超过计划离店日期，请接待人员按当前房源新订");
    if (db.count(
            "SELECT COUNT(*) FROM guest_checkin WHERE order_id=? AND guest_id=?",
            order.get("id"),
            guest)
        > 0) {
      return result(order, "该来宾已登记，本次未产生重复记录");
    }
    db.insert(
        "guest_checkin",
        map("id", id(), "order_id", order.get("id"), "guest_id", guest, "arrived_at", clock.now()));
    if ("CONFIRMED".equals(str(order, "status"))) {
      booking.transition(order, "CHECKED_IN");
    }
    return result(order, "实际到店来宾已登记");
  }

  private Map<String, Object> actual(Actor actor, Map<String, Object> body) {
    var order = hotelOrder(actor, required(body, "orderId"));
    state(order, "CHECKED_IN", "RELOCATING", "CHECKED_OUT", "CLOSED");
    LocalDate day = date(body, "date");
    require(day.isBefore(clock.today()), "DATE_CONFLICT", "仅可确认已发生的住宿晚，未来日期不能计入实际间夜");
    var night =
        db.one(
            "SELECT * FROM order_night WHERE order_id=? AND item_id=? AND stay_date=?",
            order.get("id"),
            required(body, "itemId"),
            day);
    require(bool(night, "planActive"), "DATE_CONFLICT", "该晚已取消或已转移至替代单");
    if (order.get("actualDeparture") != null) {
      require(day.isBefore(date(order, "actualDeparture")), "DATE_CONFLICT", "离店后的日期不能登记实住");
    }
    require(
        db.count(
                "SELECT COUNT(*) FROM guest_checkin WHERE order_id=? AND arrived_at<?",
                order.get("id"),
                day.plusDays(1).atTime(12, 0))
            > 0,
        "ARRIVAL_REQUIRED",
        "无对应实际到店记录");
    int quantity = integer(body, "quantity");
    require(quantity > 0 && quantity <= integer(night, "quantity"), "VALIDATION", "实际间数不能超过本单预订间数");
    var existing =
        db.find(
            "SELECT * FROM actual_room_night WHERE item_id=? AND stay_date=?",
            night.get("itemId"),
            day);
    if (existing.isPresent()) {
      require(
          integer(existing.get(), "quantity") == quantity,
          "ACTUAL_ALREADY_RECORDED",
          "该晚已有不同的实住事实，请先核查，不能重复记账");
      return result(order, "该晚实住已登记，无重复间夜");
    }
    String actual = id();
    db.insert(
        "actual_room_night",
        map(
            "id",
            actual,
            "order_id",
            order.get("id"),
            "item_id",
            night.get("itemId"),
            "stay_date",
            day,
            "quantity",
            quantity,
            "price",
            night.get("price"),
            "recorded_by",
            actor.id()));
    booking.fee(
        str(order, "id"),
        "LODGING",
        decimal(night, "price").multiply(BigDecimal.valueOf(quantity)),
        "actual:" + actual,
        "CONFIRMED",
        day + " 实际住宿，使用逐晚锁定价格",
        actor.id());
    return result(order, "实住间夜及住宿费用已保存");
  }

  /** Checks that the required past nights have recorded actual-stay facts. */
  public void ensureFacts(Map<String, Object> order, LocalDate departure) {
    var first =
        db.find(
            "SELECT arrived_at FROM guest_checkin WHERE order_id=? ORDER BY arrived_at LIMIT 1",
            order.get("id"));
    if (first.isEmpty()) {
      return;
    }
    LocalDate start = time(first.get(), "arrivedAt").toLocalDate();
    if (start.isBefore(date(order, "startDate"))) {
      start = date(order, "startDate");
    }
    LocalDate end = departure.isBefore(clock.today()) ? departure : clock.today();
    for (LocalDate day = start; day.isBefore(end); day = day.plusDays(1)) {
      require(
          db.count(
                  "SELECT COUNT(*) FROM actual_room_night WHERE order_id=? AND stay_date=?",
                  order.get("id"),
                  day)
              > 0,
          "ACTUAL_FACTS_REQUIRED",
          "请先核实并登记 " + day + " 的实际用房，不能遗漏已住间夜");
    }
  }

  private Map<String, Object> checkout(Actor actor, Map<String, Object> body) {
    var order = hotelOrder(actor, required(body, "orderId"));
    state(order, "CHECKED_IN");
    LocalDate departure = date(body, "departureDate");
    require(
        !departure.isAfter(clock.today())
            && !departure.isBefore(date(order, "startDate"))
            && !departure.isAfter(date(order, "endDate")),
        "DATE_CONFLICT",
        "实际离店日期应在住宿区间内且不能晚于今天");
    var first =
        db.one(
            "SELECT arrived_at FROM guest_checkin WHERE order_id=? ORDER BY arrived_at LIMIT 1",
            order.get("id"));
    require(
        !departure.isBefore(time(first, "arrivedAt").toLocalDate()), "DATE_CONFLICT", "离店不能早于实际到店");
    require(
        db.count(
                "SELECT COUNT(*) FROM actual_room_night WHERE order_id=? AND stay_date>=?",
                order.get("id"),
                departure)
            == 0,
        "ACTUAL_CONFLICT",
        "所选离店日期之后已有实住记录，不能覆盖");
    ensureFacts(order, departure);
    booking.revision(str(order, "id"), "CHECK_OUT");
    inventory.release(str(order, "id"), departure, true);
    db.update(
        "UPDATE guest_checkin SET departed_at=? WHERE order_id=? AND departed_at IS NULL",
        departure.equals(clock.today()) ? clock.now() : departure.atTime(23, 59, 59),
        order.get("id"));
    db.update("UPDATE booking_order SET actual_departure=? WHERE id=?", departure, order.get("id"));
    booking.transition(order, "CHECKED_OUT");
    return result(
        order,
        departure.isBefore(date(order, "endDate")) ? "已记录提前退房并释放未住日期；额外费用须另行提出及核对" : "正常退房已登记");
  }

  private Map<String, Object> late(Actor actor, Map<String, Object> body) {
    var order = hotelOrder(actor, required(body, "orderId"));
    state(order, "CONFIRMED");
    LocalDateTime until = time(body, "until");
    require(
        until.isAfter(clock.now())
            && until.isAfter(date(order, "startDate").plusDays(1).atTime(12, 0)),
        "DATE_CONFLICT",
        "另行保留期限应晚于当前时间及默认保留期限");
    required(body, "reason");
    db.update(
        "UPDATE booking_order SET late_hold_until=?,late_hold_reason=?,version=version+1 WHERE"
            + " id=?",
        until,
        required(body, "reason"),
        order.get("id"));
    notice.reception(
        "late:" + order.get("id") + ":" + until,
        order,
        "晚到保留已登记",
        "订单 " + order.get("code") + " 已另行保留至 " + until,
        false);
    return result(order, "晚到保留期限与原因已留痕");
  }

  private Map<String, Object> noShow(Actor actor, Map<String, Object> body) {
    var order = hotelOrder(actor, required(body, "orderId"));
    state(order, "CONFIRMED");
    LocalDateTime deadline = date(order, "startDate").plusDays(1).atTime(12, 0);
    if (order.get("lateHoldUntil") != null && time(order, "lateHoldUntil").isAfter(deadline)) {
      deadline = time(order, "lateHoldUntil");
    }
    require(clock.now().isAfter(deadline), "HOLD_ACTIVE", "尚在保留期限内，不能登记未入住");
    require(
        db.count("SELECT COUNT(*) FROM guest_checkin WHERE order_id=?", order.get("id")) == 0,
        "ARRIVAL_CONFLICT",
        "已有来宾到店，不能将整单登记为未入住");
    booking.revision(str(order, "id"), "NO_SHOW");
    inventory.release(str(order, "id"), clock.today(), true);
    booking.transition(order, "NO_SHOW");
    notice.reception(
        "no-show:" + order.get("id"),
        order,
        "酒店已登记未入住",
        "订单 " + order.get("code") + " 已由酒店核实并登记未入住；之后到店须新订。",
        false);
    return result(order, "未入住已登记，后续名额已释放，原始记录与费用保留");
  }

  private Map<String, Object> proposeExtension(Actor actor, Map<String, Object> body) {
    var q =
        booking.ownQuote(actor, required(body, "quoteId"), "EXTENSION", required(body, "orderId"));
    var order = hotelOrder(actor, str(q, "orderId"));
    state(order, "CHECKED_IN");
    require(
        number(order, "version") == number(q, "orderVersion"), "VERSION_CONFLICT", "订单已变化，请刷新续住方案");
    search.revalidate(q);
    String extension = id();
    db.insert(
        "extension_request",
        map(
            "id",
            extension,
            "order_id",
            order.get("id"),
            "quote_id",
            q.get("id"),
            "proposed_by",
            actor.id(),
            "status",
            "PENDING",
            "created_at",
            clock.now()));
    notice.reception(
        "extend:" + extension,
        order,
        "续住方案待确认",
        "订单 " + order.get("code") + " 的新增住宿日期与报价待负责接待人员确认。",
        false);
    var result = result(order, "已提交接待人员确认，尚未增加占用");
    result.put("extensionId", extension);
    return result;
  }

  private Map<String, Object> confirmExtension(Actor actor, Map<String, Object> body) {
    var ext = db.one("SELECT * FROM extension_request WHERE id=?", required(body, "id"));
    var before = db.one("SELECT * FROM booking_order WHERE id=?", ext.get("orderId"));
    access.responsible(actor, before);
    var order = booking.lockOrder(str(ext, "orderId"), List.of());
    access.responsible(actor, order);
    state(order, "CHECKED_IN");
    ext = db.one("SELECT * FROM extension_request WHERE id=? FOR UPDATE", ext.get("id"));
    require("PENDING".equals(str(ext, "status")), "STATE_CONFLICT", "该续住申请已处理");
    require(bool(body, "priceConfirmed"), "CONFIRMATION_REQUIRED", "请确认新增日期及申请时价格");
    var quote = search.quote(str(ext, "quoteId"));
    require(
        number(order, "version") == number(quote, "orderVersion"),
        "VERSION_CONFLICT",
        "原单已变化，请由酒店重新提出续住");
    var snapshot = search.revalidate(quote);
    Plan extra = Plan.from(object(snapshot, "plan"));
    require(extra.start().equals(date(order, "endDate")), "DATE_CONFLICT", "续住须衔接原离店日期");
    booking.revision(str(order, "id"), "EXTEND");
    inventory.applyDeltas(booking.lineDeltas(objects(snapshot, "lines"), 1));
    booking.appendLines(str(order, "id"), objects(snapshot, "lines"));
    var plan = json.read(str(order, "planJson"));
    plan.put("endDate", extra.end().toString());
    db.update(
        "UPDATE booking_order SET end_date=?,total=total+?,plan_json=?,version=version+1 WHERE"
            + " id=?",
        extra.end(),
        snapshot.get("total"),
        json.write(plan),
        order.get("id"));
    db.update("UPDATE extension_request SET status='CONFIRMED' WHERE id=?", ext.get("id"));
    return result(order, "续住已生效，原有住宿价格保持不变");
  }

  private Map<String, Object> proposeFee(Actor actor, Map<String, Object> body) {
    var order = hotelOrder(actor, required(body, "orderId"));
    String category = required(body, "category");
    require(Set.of("EXTRA", "NO_SHOW").contains(category), "VALIDATION", "费用类别无效");
    if (category.equals("EXTRA")) {
      state(order, "CHECKED_OUT");
      require(
          order.get("actualDeparture") != null
              && date(order, "actualDeparture").isBefore(date(order, "endDate")),
          "FEE_RULE",
          "仅提前退房可提出约定的额外费用");
    } else {
      state(order, "NO_SHOW");
    }
    BigDecimal amount = decimal(body, "amount");
    require(amount.signum() >= 0, "VALIDATION", "费用不能为负数");
    require(bool(body, "ruleAcknowledged"), "FEE_RULE", "须核实本单下单时已展示的约定规则");
    String note = required(body, "note");
    String feeId = id();
    booking.fee(
        str(order, "id"), category, amount, "proposal:" + feeId, "PENDING", note, actor.id());
    notice.reception(
        "fee:" + feeId, order, "约定费用待核对", "订单 " + order.get("code") + " 有独立费用，请根据下单规则核对。", false);
    return result(order, "费用已提出，等待接待人员核对，尚未标为已付款");
  }

  private Map<String, Object> reviewFee(Actor actor, Map<String, Object> body, boolean resolve) {
    var fee = db.one("SELECT * FROM fee_record WHERE id=?", required(body, "id"));
    var order = db.one("SELECT * FROM booking_order WHERE id=?", fee.get("orderId"));
    if (resolve) {
      access.roles(actor, "ADMIN");
    } else {
      access.responsible(actor, order);
    }
    order = booking.lockOrder(str(order, "id"), List.of());
    fee = db.one("SELECT * FROM fee_record WHERE id=? FOR UPDATE", fee.get("id"));
    if (resolve) {
      require("DISPUTED".equals(str(fee, "status")), "STATE_CONFLICT", "仅争议费用需要管理员裁定");
      String note = required(body, "note");
      BigDecimal amount = decimal(body, "amount");
      require(amount.signum() >= 0, "VALIDATION", "裁定费用不能为负数");
      db.update(
          "UPDATE fee_record SET status='CONFIRMED',amount=?,note=? WHERE id=?",
          amount,
          str(fee, "note") + "；管理员裁定：" + note,
          fee.get("id"));
    } else {
      require("PENDING".equals(str(fee, "status")), "STATE_CONFLICT", "该费用已核对");
      boolean accepted = bool(body, "accepted");
      String note = required(body, "note");
      db.update(
          "UPDATE fee_record SET status=?,note=? WHERE id=?",
          accepted ? "CONFIRMED" : "DISPUTED",
          str(fee, "note") + "；接待核对：" + note,
          fee.get("id"));
    }
    return result(order, "费用处理结果已保存；费用记录不代表已付款");
  }

  /** Queues reminders for expected arrivals that have not checked in. */
  public void arrivalReminders() {
    for (var order :
        db.rows(
            "SELECT * FROM booking_order WHERE status='CONFIRMED' AND start_date<?",
            clock.today())) {
      String event = "arrival-check:" + order.get("id");
      if (db.count("SELECT COUNT(*) FROM notification_task WHERE event_key=?", event) > 0) {
        continue;
      }
      notice.reception(
          event,
          order,
          "请核实未到店情况",
          "订单 " + order.get("code") + " 的计划入住日已结束，请与酒店核实。系统不会自动登记未入住。",
          false);
      notice.hotel(event, order, "请核实未到店情况", "请核实订单 " + order.get("code") + " 的到店及晚到保留情况。");
    }
  }
}
