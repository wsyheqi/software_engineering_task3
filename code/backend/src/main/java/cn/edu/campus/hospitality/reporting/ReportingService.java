package cn.edu.campus.hospitality.reporting;

import static cn.edu.campus.hospitality.shared.Data.bool;
import static cn.edu.campus.hospitality.shared.Data.date;
import static cn.edu.campus.hospitality.shared.Data.decimal;
import static cn.edu.campus.hospitality.shared.Data.integer;
import static cn.edu.campus.hospitality.shared.Data.map;
import static cn.edu.campus.hospitality.shared.Data.objects;
import static cn.edu.campus.hospitality.shared.Data.require;
import static cn.edu.campus.hospitality.shared.Data.str;
import static cn.edu.campus.hospitality.shared.Data.time;

import cn.edu.campus.hospitality.identity.Access;
import cn.edu.campus.hospitality.identity.Actor;
import cn.edu.campus.hospitality.shared.BusinessClock;
import cn.edu.campus.hospitality.shared.Db;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.stereotype.Service;

/** Builds scoped lodging and fee reports using the selected date basis. */
@Service
public class ReportingService {
  private final Db db;
  private final Access access;
  private final BusinessClock clock;

  /** Creates the report builder with database access and business-date services. */
  public ReportingService(Db db, Access access, BusinessClock clock) {
    this.db = db;
    this.access = access;
    this.clock = clock;
  }

  /** Aggregates orders, nights, guests, and fees for the permitted hotels and date range. */
  public Map<String, Object> report(
      Actor a, String basis, LocalDate from, LocalDate to, String hotel) {
    access.roles(a, "ADMIN", "RECEPTION", "HOTEL");
    require(
        Set.of("created", "planned", "actual").contains(basis), "VALIDATION", "请选择下单、计划住宿或实际住宿口径");
    require(!to.isBefore(from) && to.isBefore(from.plusDays(367)), "VALIDATION", "统计范围须在一年内");
    String scope = " WHERE 1=1";
    List<Object> args = new ArrayList<>();
    if (a.is("HOTEL")) {
      if (hotel != null && !hotel.isBlank()) {
        access.hotel(a, hotel);
      }
      scope += " AND o.hotel_id=?";
      args.add(a.hotelId());
    } else if (hotel != null && !hotel.isBlank()) {
      scope += " AND o.hotel_id=?";
      args.add(hotel);
    }
    if (a.is("RECEPTION")) {
      scope += " AND o.owner_id=?";
      args.add(a.id());
    }
    var orders =
        db.rows(
            "SELECT"
                + " o.id,o.code,o.hotel_id,o.status,o.start_date,o.end_date,o.created_at,"
                + "o.total,h.name"
                + " AS hotel_name FROM booking_order o JOIN hotel h ON o.hotel_id=h.id"
                + scope,
            args.toArray());
    Map<String, Map<String, Object>> byId = new HashMap<>();
    for (var o : orders) {
      byId.put(str(o, "id"), o);
    }
    String nightDates = basis.equals("created") ? "" : " AND n.stay_date>=? AND n.stay_date<?";
    Object[] nightArgs =
        basis.equals("created") ? args.toArray() : concat(args, from, to.plusDays(1));
    var nights =
        db.rows(
            "SELECT n.* FROM order_night n JOIN booking_order o ON o.id=n.order_id"
                + scope
                + nightDates,
            nightArgs);
    var actual =
        db.rows(
            "SELECT n.* FROM actual_room_night n JOIN booking_order o ON o.id=n.order_id"
                + scope
                + nightDates,
            nightArgs);
    final var guests =
        db.rows(
            "SELECT x.order_id,x.guest_id FROM order_guest x JOIN booking_order o ON"
                + " o.id=x.order_id"
                + scope,
            args.toArray());
    final var fees =
        db.rows(
            "SELECT f.*,o.code AS order_code FROM fee_record f JOIN booking_order o ON"
                + " o.id=f.order_id"
                + scope
                + " AND f.created_at>=? AND f.created_at<?",
            concat(args, from.atStartOfDay(), to.plusDays(1).atStartOfDay()));
    Set<String> relevant = new HashSet<>();
    if (basis.equals("created")) {
      for (var o : orders) {
        LocalDate day = time(o, "createdAt").toLocalDate();
        if (!day.isBefore(from) && !day.isAfter(to)) {
          relevant.add(str(o, "id"));
        }
      }
    } else {
      for (var n : nights) {
        relevant.add(str(n, "orderId"));
      }
      for (var n : actual) {
        relevant.add(str(n, "orderId"));
      }
    }
    Set<String> valid = Set.of("CONFIRMED", "CHECKED_IN", "CHECKED_OUT");
    Map<LocalDate, Map<String, Object>> daily = new TreeMap<>();
    for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
      daily.put(
          d,
          map(
              "date",
              d,
              "planned",
              0,
              "actual",
              0,
              "plannedCost",
              BigDecimal.ZERO,
              "actualCost",
              BigDecimal.ZERO));
    }
    int plannedCount = 0;
    int actualCount = 0;
    int future = 0;
    BigDecimal plannedCost = BigDecimal.ZERO;
    BigDecimal actualCost = BigDecimal.ZERO;
    Set<String> actualOrders = new HashSet<>();
    for (var n : nights) {
      String oid = str(n, "orderId");
      if (!byId.containsKey(oid)
          || !relevant.contains(oid)
          || !valid.contains(str(byId.get(oid), "status"))
          || !bool(n, "planActive")) {
        continue;
      }
      int qty = integer(n, "quantity");
      BigDecimal cost = decimal(n, "price").multiply(BigDecimal.valueOf(qty));
      plannedCount += qty;
      plannedCost = plannedCost.add(cost);
      if (!date(n, "stayDate").isBefore(clock.today())) {
        future += qty;
      }
      var day = daily.computeIfAbsent(date(n, "stayDate"), this::emptyDay);
      day.put("planned", integer(day, "planned") + qty);
      day.put("plannedCost", decimal(day, "plannedCost").add(cost));
    }
    for (var n : actual) {
      String oid = str(n, "orderId");
      if (!relevant.contains(oid) || !date(n, "stayDate").isBefore(clock.today())) {
        continue;
      }
      int qty = integer(n, "quantity");
      BigDecimal cost = decimal(n, "price").multiply(BigDecimal.valueOf(qty));
      actualCount += qty;
      actualCost = actualCost.add(cost);
      actualOrders.add(oid);
      var day = daily.computeIfAbsent(date(n, "stayDate"), this::emptyDay);
      day.put("actual", integer(day, "actual") + qty);
      day.put("actualCost", decimal(day, "actualCost").add(cost));
    }
    Set<String> counted = new HashSet<>();
    Set<String> unique = new HashSet<>();
    int visits = 0;
    for (var g : guests) {
      String oid = str(g, "orderId");
      boolean include =
          basis.equals("actual")
              ? actualOrders.contains(oid)
              : relevant.contains(oid) && valid.contains(str(byId.get(oid), "status"));
      if (include && counted.add(oid + ":" + str(g, "guestId"))) {
        visits++;
        unique.add(str(g, "guestId"));
      }
    }
    Map<String, Integer> statuses = new LinkedHashMap<>();
    for (var o : orders) {
      if (relevant.contains(str(o, "id"))) {
        statuses.merge(str(o, "status"), 1, Integer::sum);
      }
    }
    Map<String, BigDecimal> categories = new LinkedHashMap<>();
    for (String key : List.of("CANCELLATION", "NO_SHOW", "EXTRA")) {
      categories.put(key, BigDecimal.ZERO);
    }
    BigDecimal pending = BigDecimal.ZERO;
    for (var fee : fees) {
      if (!str(fee, "category").equals("LODGING")) {
        if (str(fee, "status").equals("CONFIRMED")) {
          categories.merge(str(fee, "category"), decimal(fee, "amount"), BigDecimal::add);
        } else {
          pending = pending.add(decimal(fee, "amount"));
        }
      }
    }
    return map(
        "basis",
        basis,
        "from",
        from,
        "to",
        to,
        "visits",
        visits,
        "uniquePeople",
        unique.size(),
        "orderCount",
        relevant.size(),
        "plannedNights",
        plannedCount,
        "actualNights",
        actualCount,
        "futureNights",
        future,
        "plannedCost",
        plannedCost,
        "actualCost",
        actualCost,
        "fees",
        categories,
        "pendingFees",
        pending,
        "statusCounts",
        statuses,
        "daily",
        new ArrayList<>(daily.values()),
        "orders",
        orders.stream().filter(o -> relevant.contains(str(o, "id"))).toList(),
        "feeDetails",
        fees.stream().filter(f -> !str(f, "category").equals("LODGING")).toList(),
        "notes",
        "间夜按当前日期口径逐日归属；非住宿费用按登记日期归属，待核对与争议费用不计入已核对费用。记录费用不代表已付款。");
  }

  private Object[] concat(List<Object> base, Object... more) {
    List<Object> result = new ArrayList<>(base);
    result.addAll(Arrays.asList(more));
    return result.toArray();
  }

  private Map<String, Object> emptyDay(LocalDate day) {
    return map(
        "date",
        day,
        "planned",
        0,
        "actual",
        0,
        "plannedCost",
        BigDecimal.ZERO,
        "actualCost",
        BigDecimal.ZERO);
  }

  /** Serializes the report's daily lodging and fee rows as CSV. */
  public String csv(Map<String, Object> r) {
    StringBuilder csv = new StringBuilder("\uFEFF日期,计划间夜,实际间夜,计划住宿费用,实际住宿费用\r\n");
    for (var d : objects(r, "daily")) {
      csv.append(str(d, "date"))
          .append(',')
          .append(d.get("planned"))
          .append(',')
          .append(d.get("actual"))
          .append(',')
          .append(d.get("plannedCost"))
          .append(',')
          .append(d.get("actualCost"))
          .append("\r\n");
    }
    return csv.toString();
  }
}
