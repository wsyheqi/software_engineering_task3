package cn.edu.campus.hospitality.relocation;

import static cn.edu.campus.hospitality.shared.Data.bool;
import static cn.edu.campus.hospitality.shared.Data.date;
import static cn.edu.campus.hospitality.shared.Data.id;
import static cn.edu.campus.hospitality.shared.Data.map;
import static cn.edu.campus.hospitality.shared.Data.number;
import static cn.edu.campus.hospitality.shared.Data.require;
import static cn.edu.campus.hospitality.shared.Data.required;
import static cn.edu.campus.hospitality.shared.Data.state;
import static cn.edu.campus.hospitality.shared.Data.str;

import cn.edu.campus.hospitality.booking.BookingService;
import cn.edu.campus.hospitality.identity.Access;
import cn.edu.campus.hospitality.identity.Actor;
import cn.edu.campus.hospitality.inventory.InventoryService;
import cn.edu.campus.hospitality.notification.NotificationService;
import cn.edu.campus.hospitality.search.SearchService;
import cn.edu.campus.hospitality.shared.BusinessClock;
import cn.edu.campus.hospitality.shared.CommandHandler;
import cn.edu.campus.hospitality.shared.Db;
import cn.edu.campus.hospitality.stay.StayService;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/** Handles hotel closures and replacement reservations for affected orders. */
@Service
public class RelocationService implements CommandHandler {
  private final Db db;
  private final Access access;
  private final InventoryService inventory;
  private final BookingService booking;
  private final SearchService search;
  private final StayService stay;
  private final NotificationService notice;
  private final BusinessClock clock;

  /** Creates the relocation handler with booking, stay, and notification services. */
  public RelocationService(
      Db db,
      Access access,
      InventoryService inventory,
      BookingService booking,
      SearchService search,
      StayService stay,
      NotificationService notice,
      BusinessClock clock) {
    this.db = db;
    this.access = access;
    this.inventory = inventory;
    this.booking = booking;
    this.search = search;
    this.stay = stay;
    this.notice = notice;
    this.clock = clock;
  }

  @Override
  public Set<String> actions() {
    return Set.of("CLOSURE", "RELOCATE");
  }

  @Override
  public Map<String, Object> handle(Actor actor, String action, Map<String, Object> body) {
    return action.equals("CLOSURE") ? close(actor, body) : relocate(actor, body);
  }

  private Map<String, Object> close(Actor actor, Map<String, Object> body) {
    String hotel = required(body, "hotelId");
    access.hotel(actor, hotel);
    LocalDate start = date(body, "startDate");
    LocalDate end = date(body, "endDate");
    require(end.isAfter(start), "VALIDATION", "停业结束日须晚于开始日");
    String reason = required(body, "reason");
    inventory.lockHotels(List.of(hotel), true);
    var affected =
        db.rows(
            "SELECT * FROM booking_order WHERE hotel_id=? AND status IN ('CONFIRMED','CHECKED_IN')"
                + " AND start_date<? AND end_date>? ORDER BY id FOR UPDATE",
            hotel,
            end,
            start);
    for (var order : affected) {
      if ("CHECKED_IN".equals(str(order, "status"))) {
        stay.ensureFacts(order, clock.today());
      }
    }
    String closure = id();
    db.insert(
        "hotel_closure",
        map(
            "id",
            closure,
            "hotel_id",
            hotel,
            "start_date",
            start,
            "end_date",
            end,
            "reason",
            reason,
            "created_at",
            clock.now()));
    db.update(
        "UPDATE daily_inventory SET stop_sale=TRUE,version=version+1 WHERE hotel_id=? AND"
            + " stay_date>=? AND stay_date<?",
        hotel,
        start,
        end);
    for (var order : affected) {
      booking.revision(str(order, "id"), "CLOSURE");
      LocalDate remaining =
          "CHECKED_IN".equals(str(order, "status")) ? clock.today() : date(order, "startDate");
      if (remaining.isBefore(date(order, "startDate"))) {
        remaining = date(order, "startDate");
      }
      require(remaining.isBefore(date(order, "endDate")), "DATE_CONFLICT", "已无剩余住宿日期，请先办理正常退房");
      db.insert(
          "relocation_case",
          map(
              "id",
              id(),
              "order_id",
              order.get("id"),
              "closure_id",
              closure,
              "remaining_start",
              remaining,
              "remaining_end",
              order.get("endDate"),
              "status",
              "OPEN"));
      booking.transition(order, "RELOCATING");
      notice.reception(
          "closure:" + closure + ":" + order.get("id"),
          order,
          "酒店停业，住宿待安置",
          "订单 " + order.get("code") + " 因酒店停业进入待安置。原占用保留，请选择并确认符合原条件的替代方案。",
          true);
    }
    return map(
        "businessCode",
        "CLOSURE_RECORDED",
        "message",
        "受影响日期已停售，相关订单已转待安置，原占用保留",
        "hotelId",
        hotel,
        "closureId",
        closure,
        "affectedCount",
        affected.size());
  }

  private Map<String, Object> relocate(Actor actor, Map<String, Object> body) {
    String caseId = required(body, "id");
    var initial = db.one("SELECT * FROM relocation_case WHERE id=?", caseId);
    var before = db.one("SELECT * FROM booking_order WHERE id=?", initial.get("orderId"));
    access.responsible(actor, before);
    var quote = booking.ownQuote(actor, required(body, "quoteId"), "RELOCATION", str(before, "id"));
    var order = booking.lockOrder(str(before, "id"), List.of(str(quote, "hotelId")));
    access.responsible(actor, order);
    var c = db.one("SELECT * FROM relocation_case WHERE id=? FOR UPDATE", caseId);
    if (c.get("newOrderId") != null) {
      return map(
          "businessCode",
          "RELOCATED",
          "message",
          "本次安置已完成，返回原关联新单",
          "orderId",
          c.get("newOrderId"),
          "originalOrderId",
          order.get("id"),
          "hotelId",
          quote.get("hotelId"));
    }
    state(order, "RELOCATING");
    require("OPEN".equals(str(c, "status")), "STATE_CONFLICT", "安置记录已关闭");
    require(
        number(order, "version") == number(quote, "orderVersion"),
        "VERSION_CONFLICT",
        "待安置订单已变化，请刷新方案");
    require(bool(body, "confirmed"), "CONFIRMATION_REQUIRED", "请先确认替代方案与适用费用规则");
    var snapshot = search.revalidate(quote);
    stay.ensureFacts(order, date(c, "remainingStart"));
    final String newOrder =
        booking.createConfirmed(
            str(order, "ownerId"), str(order, "demandId"), snapshot, str(order, "id"), true);
    // Allocating the new order and closing/releasing the original share the caller transaction.
    inventory.release(str(order, "id"), LocalDate.of(1900, 1, 1), false);
    db.update(
        "UPDATE order_night SET plan_active=FALSE WHERE order_id=? AND stay_date>=?",
        order.get("id"),
        c.get("remainingStart"));
    db.update(
        "UPDATE guest_checkin SET departed_at=? WHERE order_id=? AND departed_at IS NULL",
        clock.now(),
        order.get("id"));
    db.update(
        "UPDATE booking_order SET actual_departure=? WHERE id=?",
        c.get("remainingStart"),
        order.get("id"));
    booking.transition(order, "CLOSED");
    db.update(
        "UPDATE relocation_case SET new_order_id=?,status='RESOLVED' WHERE id=?", newOrder, caseId);
    notice.reception(
        "relocated:" + caseId,
        order,
        "替代住宿已确认",
        "原订单 " + order.get("code") + " 已完成安置，原单保留历史，新单承接剩余住宿日期。",
        false);
    return map(
        "businessCode",
        "RELOCATED",
        "message",
        "替代新单已确认，原单已关闭并释放占用",
        "orderId",
        newOrder,
        "originalOrderId",
        order.get("id"),
        "hotelId",
        snapshot.get("hotelId"));
  }
}
