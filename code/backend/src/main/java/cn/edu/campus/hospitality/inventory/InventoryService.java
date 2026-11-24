package cn.edu.campus.hospitality.inventory;

import static cn.edu.campus.hospitality.shared.Data.bool;
import static cn.edu.campus.hospitality.shared.Data.date;
import static cn.edu.campus.hospitality.shared.Data.decimal;
import static cn.edu.campus.hospitality.shared.Data.id;
import static cn.edu.campus.hospitality.shared.Data.integer;
import static cn.edu.campus.hospitality.shared.Data.map;
import static cn.edu.campus.hospitality.shared.Data.number;
import static cn.edu.campus.hospitality.shared.Data.objects;
import static cn.edu.campus.hospitality.shared.Data.require;
import static cn.edu.campus.hospitality.shared.Data.required;
import static cn.edu.campus.hospitality.shared.Data.str;

import cn.edu.campus.hospitality.audit.AuditService;
import cn.edu.campus.hospitality.identity.Access;
import cn.edu.campus.hospitality.identity.Actor;
import cn.edu.campus.hospitality.shared.BusinessClock;
import cn.edu.campus.hospitality.shared.Db;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Tracks nightly room availability, hotel inventory changes, and allocations. */
@Service
public class InventoryService {
  private final Db db;
  private final Access access;
  private final AuditService audit;
  private final BusinessClock clock;
  private final boolean h2;

  /** Creates the inventory service with lock behavior appropriate for the configured database. */
  public InventoryService(
      Db db,
      Access access,
      AuditService audit,
      BusinessClock clock,
      @Value("${spring.datasource.url}") String url) {
    this.db = db;
    this.access = access;
    this.audit = audit;
    this.clock = clock;
    this.h2 = url.startsWith("jdbc:h2:");
  }

  /** Locks the specified hotel rows in identifier order and rejects deleted hotels. */
  public void lockHotels(Collection<String> hotelIds, boolean exclusive) {
    hotelIds.stream()
        .filter(s -> s != null && !s.isBlank())
        .distinct()
        .sorted()
        .forEach(
            h -> {
              var row =
                  db.one(
                      "SELECT * FROM hotel WHERE id=? "
                          + (exclusive || h2 ? "FOR UPDATE" : "FOR SHARE"),
                      h);
              require(row.get("deletedAt") == null, "HOTEL_DELETED", "酒店已删除，不能再修改资料或预订房源");
            });
  }

  /** Reads nightly inventory in the half-open date range, optionally locking its rows. */
  public List<Map<String, Object>> range(
      LocalDate start, LocalDate end, String hotel, boolean lock) {
    String sql =
        "SELECT i.*,r.code,r.name AS room_name,r.capacity FROM daily_inventory i JOIN room_type r"
            + " ON r.id=i.room_type_id WHERE i.stay_date>=? AND i.stay_date<?";
    List<Object> args = new ArrayList<>(List.of(start, end));
    if (hotel != null) {
      sql += " AND i.hotel_id=?";
      args.add(hotel);
    }
    sql += " ORDER BY i.hotel_id,i.room_type_id,i.stay_date";
    return db.rows(sql + (lock ? " FOR UPDATE" : ""), args.toArray());
  }

  /** Lists the permitted hotel's daily prices and effective room availability. */
  public List<Map<String, Object>> list(Actor actor, String hotel, LocalDate start, LocalDate end) {
    access.roles(actor, "HOTEL", "ADMIN");
    if (actor.is("HOTEL")) {
      access.hotel(actor, hotel);
    }
    require(
        !end.isBefore(start) && end.isBefore(start.plusDays(181)), "VALIDATION", "日期范围应在 180 天内");
    return range(start, end.plusDays(1), hotel, false).stream()
        .map(
            row -> {
              row.put(
                  "available",
                  bool(row, "stopSale")
                      ? 0
                      : integer(row, "quota") - integer(row, "blocked") - integer(row, "occupied"));
              return row;
            })
        .toList();
  }

  /** Validates all inventory changes before applying any of them in the current transaction. */
  public void applyDeltas(Map<String, Integer> deltas) {
    if (deltas.isEmpty()) {
      return;
    }
    String marks = String.join(",", Collections.nCopies(deltas.size(), "?"));
    var rows =
        db.rows(
            "SELECT * FROM daily_inventory WHERE id IN ("
                + marks
                + ") ORDER BY hotel_id,room_type_id,stay_date FOR UPDATE",
            deltas.keySet().toArray());
    require(rows.size() == deltas.size(), "INVENTORY_MISSING", "部分逐日房源缺失");
    for (var row : rows) {
      int delta = deltas.get(str(row, "id"));
      int after = integer(row, "occupied") + delta;
      require(after >= 0, "INVENTORY_CONFLICT", "名额释放记录不一致");
      require(
          delta <= 0
              || (!bool(row, "stopSale")
                  && after <= integer(row, "quota") - integer(row, "blocked")),
          "INSUFFICIENT_INVENTORY",
          "某一晚房源不足，整单未生效");
    }
    for (var row : rows) {
      int delta = deltas.get(str(row, "id"));
      if (delta != 0) {
        db.update(
            "UPDATE daily_inventory SET occupied=occupied+?,version=version+1 WHERE id=?",
            delta,
            row.get("id"));
      }
    }
  }

  /** Applies version-checked daily price and quota changes for the permitted hotel. */
  public Map<String, Object> update(Actor actor, Map<String, Object> input) {
    String hotel = required(input, "hotelId");
    access.hotel(actor, hotel);
    lockHotels(List.of(hotel), false);
    var entries = objects(input, "entries");
    require(!entries.isEmpty() && entries.size() <= 500, "VALIDATION", "请提供 1～500 条每日记录");
    Set<String> ids = new HashSet<>();
    for (var e : entries) {
      require(ids.add(required(e, "id")), "VALIDATION", "同一每日房源不能重复提交");
    }
    String marks = String.join(",", Collections.nCopies(ids.size(), "?"));
    Map<String, String> lockKeys = new HashMap<>();
    for (var row :
        db.rows("SELECT * FROM daily_inventory WHERE id IN (" + marks + ")", ids.toArray())) {
      access.check(
          actor, hotel.equals(str(row, "hotelId")), str(row, "id"), hotel, null, "房源不属于本酒店");
      lockKeys.put(
          str(row, "id"),
          str(row, "hotelId") + "/" + str(row, "roomTypeId") + "/" + str(row, "stayDate"));
    }
    require(lockKeys.size() == ids.size(), "NOT_FOUND", "部分每日房源不存在");
    List<Map<String, Object>> sorted = new ArrayList<>(entries);
    sorted.sort(Comparator.comparing(e -> lockKeys.get(str(e, "id"))));
    List<Map<String, Object>> current = new ArrayList<>();
    for (var entry : sorted) {
      var row =
          db.one("SELECT * FROM daily_inventory WHERE id=? FOR UPDATE", required(entry, "id"));
      access.check(
          actor, hotel.equals(str(row, "hotelId")), str(row, "id"), hotel, null, "房源不属于本酒店");
      require(
          number(entry, "version") == number(row, "version"), "VERSION_CONFLICT", "房源已变化，请刷新后修改");
      require(
          decimal(entry, "price").signum() >= 0
              && integer(entry, "quota") >= integer(row, "occupied") + integer(row, "blocked"),
          "QUOTA_CONFLICT",
          "学校名额不得低于已占用与停用数量之和");
      current.add(row);
    }
    for (int i = 0; i < sorted.size(); i++) {
      var entry = sorted.get(i);
      var row = current.get(i);
      db.update(
          "UPDATE daily_inventory SET price=?,quota=?,version=version+1 WHERE id=?",
          decimal(entry, "price"),
          integer(entry, "quota"),
          row.get("id"));
      audit.record(
          actor, "INVENTORY_UPDATE", str(row, "id"), hotel, null, null, "SUCCESS", "维护每日价格及学校名额");
    }
    return map("businessCode", "INVENTORY_UPDATED", "message", "每日价格与名额已更新");
  }

  /** Records a room block and applies it when occupied rooms are unaffected. */
  public Map<String, Object> block(Actor actor, Map<String, Object> input) {
    String hotel = required(input, "hotelId");
    String room = required(input, "roomTypeId");
    access.hotel(actor, hotel);
    lockHotels(List.of(hotel), false);
    var type = db.one("SELECT * FROM room_type WHERE id=? AND hotel_id=?", room, hotel);
    LocalDate start = date(input, "startDate");
    LocalDate end = date(input, "endDate");
    int quantity = integer(input, "quantity");
    require(end.isAfter(start) && quantity > 0, "VALIDATION", "停用日期或数量无效");
    if ("CLEANING".equals(str(input, "kind")) && bool(input, "readyBeforeCheckin")) {
      return map("businessCode", "CLEANING_READY", "message", "清洁可在入住前完成，不扣减当晚名额");
    }
    var rows =
        db.rows(
            "SELECT * FROM daily_inventory WHERE room_type_id=? AND stay_date>=? AND stay_date<?"
                + " ORDER BY stay_date FOR UPDATE",
            room,
            start,
            end);
    require(
        rows.size() == java.time.temporal.ChronoUnit.DAYS.between(start, end),
        "INVENTORY_MISSING",
        "停用区间缺少每日记录");
    boolean conflict =
        rows.stream()
            .anyMatch(
                r ->
                    integer(r, "quota") - integer(r, "blocked") - integer(r, "occupied")
                        < quantity);
    String block = id();
    db.insert(
        "inventory_block",
        map(
            "id",
            block,
            "hotel_id",
            hotel,
            "room_type_id",
            room,
            "start_date",
            start,
            "end_date",
            end,
            "quantity",
            quantity,
            "reason",
            required(input, "reason"),
            "status",
            conflict ? "PENDING" : "APPLIED",
            "created_at",
            clock.now()));
    if (conflict) {
      db.insert(
          "work_item",
          map(
              "id",
              id(),
              "source_key",
              "block:" + block,
              "kind",
              "INVENTORY_CONFLICT",
              "hotel_id",
              hotel,
              "title",
              "维修影响已确认订单",
              "detail",
              "请协调受影响预订后重新处理；既有占用未被覆盖",
              "status",
              "OPEN",
              "created_at",
              clock.now()));
    } else {
      for (var row : rows) {
        db.update(
            "UPDATE daily_inventory SET blocked=blocked+?,version=version+1 WHERE id=?",
            quantity,
            row.get("id"));
      }
    }
    audit.record(
        actor,
        "INVENTORY_BLOCK",
        block,
        hotel,
        null,
        null,
        conflict ? "PENDING" : "SUCCESS",
        "登记维修/清洁停用区间");
    return map(
        "businessCode",
        conflict ? "BLOCK_PENDING" : "BLOCK_APPLIED",
        "message",
        conflict ? "与已有占用冲突，已转待处理，原占用未改变" : "指定日期名额已扣减",
        "blockId",
        block);
  }

  /** Returns the nightly inventory quantities currently allocated to an order. */
  public Map<String, Integer> held(String orderId) {
    Map<String, Integer> result = new LinkedHashMap<>();
    for (var r :
        db.rows(
            "SELECT inventory_id,quantity FROM order_night WHERE order_id=? AND released=FALSE",
            orderId)) {
      result.merge(str(r, "inventoryId"), integer(r, "quantity"), Integer::sum);
    }
    return result;
  }

  /** Releases an order's nights from the given date and optionally deactivates their plans. */
  public void release(String orderId, LocalDate from, boolean deactivatePlan) {
    var rows =
        db.rows(
            "SELECT * FROM order_night WHERE order_id=? AND released=FALSE AND stay_date>=? ORDER"
                + " BY inventory_id",
            orderId,
            from);
    Map<String, Integer> deltas = new LinkedHashMap<>();
    for (var row : rows) {
      deltas.merge(str(row, "inventoryId"), -integer(row, "quantity"), Integer::sum);
    }
    applyDeltas(deltas);
    db.update(
        "UPDATE order_night SET released=TRUE"
            + (deactivatePlan ? ",plan_active=FALSE" : "")
            + " WHERE order_id=? AND released=FALSE AND stay_date>=?",
        orderId,
        from);
  }
}
