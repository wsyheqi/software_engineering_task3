package cn.edu.campus.hospitality.search;

import static cn.edu.campus.hospitality.shared.Data.bool;
import static cn.edu.campus.hospitality.shared.Data.decimal;
import static cn.edu.campus.hospitality.shared.Data.id;
import static cn.edu.campus.hospitality.shared.Data.integer;
import static cn.edu.campus.hospitality.shared.Data.map;
import static cn.edu.campus.hospitality.shared.Data.number;
import static cn.edu.campus.hospitality.shared.Data.object;
import static cn.edu.campus.hospitality.shared.Data.require;
import static cn.edu.campus.hospitality.shared.Data.required;
import static cn.edu.campus.hospitality.shared.Data.state;
import static cn.edu.campus.hospitality.shared.Data.str;

import cn.edu.campus.hospitality.identity.Access;
import cn.edu.campus.hospitality.identity.AccountLocks;
import cn.edu.campus.hospitality.identity.Actor;
import cn.edu.campus.hospitality.inventory.InventoryService;
import cn.edu.campus.hospitality.shared.BusinessClock;
import cn.edu.campus.hospitality.shared.BusinessException;
import cn.edu.campus.hospitality.shared.Db;
import cn.edu.campus.hospitality.shared.Json;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Searches hotel availability and stores the priced plans offered to the user. */
@Service
public class SearchService {
  private final Db db;
  private final Json json;
  private final Access access;
  private final InventoryService inventory;
  private final BusinessClock clock;
  private final AccountLocks accountLocks;

  /** Creates the search service with inventory, authorization, and quote persistence. */
  public SearchService(
      Db db,
      Json json,
      Access access,
      InventoryService inventory,
      BusinessClock clock,
      AccountLocks accountLocks) {
    this.db = db;
    this.json = json;
    this.access = access;
    this.inventory = inventory;
    this.clock = clock;
    this.accountLocks = accountLocks;
  }

  /** Checks the reception location and the actor's responsibility for every guest. */
  public void validateGuests(Actor a, Plan p) {
    db.one("SELECT * FROM reception_site WHERE id=?", p.siteId());
    for (String guest : p.guestIds()) {
      var row = db.one("SELECT * FROM guest WHERE id=?", guest);
      access.check(
          a,
          a.is("RECEPTION") && a.id().equals(str(row, "ownerId")),
          guest,
          null,
          null,
          "来宾不在负责范围内");
    }
  }

  /** Saves a validated demand, checking its version when editing an existing one. */
  @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
  public Map<String, Object> saveDemand(Actor actor, Map<String, Object> body) {
    actor = accountLocks.active(actor);
    access.roles(actor, "RECEPTION");
    Plan plan = Plan.from(body);
    validateGuests(actor, plan);
    String demand = str(body, "id");
    if (demand.isBlank()) {
      demand = id();
      db.insert(
          "stay_demand",
          map(
              "id",
              demand,
              "owner_id",
              actor.id(),
              "plan_json",
              json.write(plan.view()),
              "version",
              1,
              "created_at",
              clock.now()));
    } else {
      var old = db.one("SELECT * FROM stay_demand WHERE id=? FOR UPDATE", demand);
      access.check(actor, actor.id().equals(str(old, "ownerId")), demand, null, null, "需求不在负责范围内");
      require(number(body, "version") == number(old, "version"), "VERSION_CONFLICT", "需求已被修改，请刷新");
      db.update(
          "UPDATE stay_demand SET plan_json=?,version=version+1 WHERE id=?",
          json.write(plan.view()),
          demand);
    }
    return demand(actor, demand);
  }

  /** Loads the actor's own demand together with its current version. */
  public Map<String, Object> demand(Actor actor, String id) {
    var row = db.one("SELECT * FROM stay_demand WHERE id=?", id);
    access.check(
        actor,
        actor.is("RECEPTION") && actor.id().equals(str(row, "ownerId")),
        id,
        null,
        null,
        "需求不在负责范围内");
    return map("id", id, "version", row.get("version"), "plan", json.read(str(row, "planJson")));
  }

  /** Filters and ranks hotels for a booking, change, relocation, or extension. */
  @Transactional
  public List<Map<String, Object>> search(Actor actor, Map<String, Object> body) {
    String mode = str(body, "mode");
    if (mode.isBlank()) {
      mode = "INITIAL";
    }
    require(
        Set.of("INITIAL", "CHANGE", "RELOCATION", "EXTENSION").contains(mode),
        "VALIDATION",
        "方案类型无效");
    String demandId = null;
    String orderId = null;
    String orderHotelId = null;
    long demandVersion = 0;
    long orderVersion = 0;
    Plan plan;
    if (mode.equals("INITIAL")) {
      access.roles(actor, "RECEPTION");
      demandId = required(body, "demandId");
      var d = demand(actor, demandId);
      demandVersion = number(d, "version");
      plan = Plan.from(object(d, "plan"));
      require(!plan.start().isBefore(clock.today()), "DATE_CONFLICT", "不能新订过去日期");
    } else {
      orderId = required(body, "orderId");
      var order = db.one("SELECT * FROM booking_order WHERE id=?", orderId);
      orderVersion = number(order, "version");
      orderHotelId = str(order, "hotelId");
      if (mode.equals("EXTENSION") && actor.is("HOTEL")) {
        access.hotel(actor, orderHotelId);
      } else {
        access.responsible(actor, order);
      }
      Map<String, Object> p = json.read(str(order, "planJson"));
      if (mode.equals("CHANGE")) {
        state(order, "CONFIRMED");
        p.putAll(object(body, "plan"));
      }
      if (mode.equals("RELOCATION")) {
        state(order, "RELOCATING");
        var c = db.one("SELECT * FROM relocation_case WHERE order_id=? AND status='OPEN'", orderId);
        p.put("startDate", str(c, "remainingStart"));
        p.put("endDate", str(c, "remainingEnd"));
      }
      if (mode.equals("EXTENSION")) {
        state(order, "CHECKED_IN");
        p.put("startDate", str(order, "endDate"));
        p.put("endDate", required(body, "endDate"));
      }
      plan = Plan.from(p);
      if (actor.is("RECEPTION")) {
        validateGuests(actor, plan);
      }
    }
    var hotels =
        db.rows(
            "SELECT h.*,d.distance_km,d.version AS distance_version,r.version AS"
                + " rule_version,r.cancellation_text,r.early_checkout_text,r.no_show_text FROM"
                + " hotel h JOIN hotel_site_distance d ON h.id=d.hotel_id JOIN fee_rule r ON"
                + " h.id=r.hotel_id WHERE d.site_id=? AND h.city=? AND h.deleted_at IS NULL ORDER"
                + " BY h.id",
            plan.siteId(),
            plan.city());
    var all = inventory.range(plan.start(), plan.end(), null, false);
    Map<String, List<Map<String, Object>>> grouped = new HashMap<>();
    for (var row : all) {
      grouped.computeIfAbsent(str(row, "hotelId"), k -> new ArrayList<>()).add(row);
    }
    List<Map<String, Object>> options = new ArrayList<>();
    for (var hotel : hotels) {
      String hotelId = str(hotel, "id");
      if (mode.equals("EXTENSION") && !hotelId.equals(orderHotelId)) {
        continue;
      }
      if (mode.equals("RELOCATION") && hotelId.equals(orderHotelId)) {
        continue;
      }
      try {
        var snapshot = build(plan, hotel, grouped.getOrDefault(hotelId, List.of()), mode, orderId);
        String q = id();
        db.insert(
            "quote_snapshot",
            map(
                "id",
                q,
                "actor_id",
                actor.id(),
                "demand_id",
                demandId,
                "demand_version",
                demandVersion,
                "hotel_id",
                hotel.get("id"),
                "order_id",
                orderId,
                "mode",
                mode,
                "order_version",
                orderVersion,
                "snapshot_json",
                json.write(snapshot),
                "snapshot_hash",
                json.hash(snapshot),
                "created_at",
                clock.now()));
        options.add(map("quoteId", q, "mode", mode, "snapshot", snapshot));
      } catch (BusinessException ex) {
        if (!Set.of(
                "NOT_QUALIFIED",
                "INSUFFICIENT_INVENTORY",
                "CAPACITY",
                "BUDGET",
                "DISTANCE",
                "PRICE_RANGE")
            .contains(ex.code())) {
          throw ex;
        }
      }
    }
    Comparator<Map<String, Object>> c =
        Comparator.comparing(
                (Map<String, Object> o) -> decimal(object(o, "snapshot"), "scoreNumerator"))
            .reversed();
    if ("price".equals(str(body, "sort"))) {
      c = Comparator.comparing(o -> decimal(object(o, "snapshot"), "total"));
    }
    if ("distance".equals(str(body, "sort"))) {
      c = Comparator.comparing(o -> decimal(object(o, "snapshot"), "distance"));
    }
    c =
        c.thenComparing(o -> decimal(object(o, "snapshot"), "total"))
            .thenComparing(o -> decimal(object(o, "snapshot"), "distance"))
            .thenComparing(
                o -> decimal(object(o, "snapshot"), "evaluation"), Comparator.reverseOrder())
            .thenComparing(o -> str(object(o, "snapshot"), "hotelId"));
    options.sort(c);
    return options;
  }

  /** Loads the saved quote row used by booking and confirmation checks. */
  public Map<String, Object> quote(String quoteId) {
    return db.one("SELECT * FROM quote_snapshot WHERE id=?", quoteId);
  }

  /** Reprices a quote using current records and requests confirmation if the snapshot changed. */
  public Map<String, Object> revalidate(Map<String, Object> quote) {
    Plan plan = Plan.from(object(json.read(str(quote, "snapshotJson")), "plan"));
    if ("INITIAL".equals(str(quote, "mode"))) {
      var d = db.one("SELECT * FROM stay_demand WHERE id=? FOR UPDATE", quote.get("demandId"));
      require(
          number(d, "version") == number(quote, "demandVersion"),
          "DEMAND_CHANGED",
          "住宿需求已变化，请重新查询并确认");
    }
    var hotel =
        db.one(
            "SELECT h.*,d.distance_km,d.version AS distance_version,r.version AS"
                + " rule_version,r.cancellation_text,r.early_checkout_text,r.no_show_text FROM"
                + " hotel h JOIN hotel_site_distance d ON h.id=d.hotel_id JOIN fee_rule r ON"
                + " h.id=r.hotel_id WHERE h.id=? AND d.site_id=?",
            quote.get("hotelId"),
            plan.siteId());
    var current =
        build(
            plan,
            hotel,
            inventory.range(plan.start(), plan.end(), str(quote, "hotelId"), true),
            str(quote, "mode"),
            str(quote, "orderId"));
    if (!json.hash(current).equals(str(quote, "snapshotHash"))) {
      throw new BusinessException(
          "NEEDS_RECONFIRMATION", "价格、名额或适用规则已变化，请查看最新方案并重新确认", map("latest", current), 409);
    }
    return current;
  }

  private Map<String, Object> build(
      Plan plan,
      Map<String, Object> hotel,
      List<Map<String, Object>> rows,
      String mode,
      String orderId) {
    require(hotel.get("deletedAt") == null, "HOTEL_DELETED", "酒店已删除，请重新选择合格酒店");
    require(
        bool(hotel, "audited")
            && hotel.get("evaluation") != null
            && decimal(hotel, "evaluation").compareTo(new BigDecimal("70")) >= 0,
        "NOT_QUALIFIED",
        "酒店未通过学校质量条件");
    BigDecimal distance = decimal(hotel, "distanceKm");
    final BigDecimal evaluation = decimal(hotel, "evaluation");
    require(distance.compareTo(plan.maxDistance()) <= 0, "DISTANCE", "酒店超出最大距离");
    Map<String, Integer> own = mode.equals("CHANGE") ? inventory.held(orderId) : Map.of();
    List<Map<String, Object>> lines = new ArrayList<>();
    BigDecimal total = BigDecimal.ZERO;
    int capacity = 0;
    for (var need : plan.items()) {
      String code = str(need, "code");
      int quantity = integer(need, "quantity");
      var selected = rows.stream().filter(r -> code.equals(str(r, "code"))).toList();
      require(selected.size() == plan.dates().size(), "INSUFFICIENT_INVENTORY", "部分住宿晚无完整价格或名额");
      capacity += integer(selected.get(0), "capacity") * quantity;
      for (var row : selected) {
        int available =
            bool(row, "stopSale")
                ? 0
                : integer(row, "quota")
                    - integer(row, "occupied")
                    - integer(row, "blocked")
                    + own.getOrDefault(str(row, "id"), 0);
        require(available >= quantity, "INSUFFICIENT_INVENTORY", "某一晚学校名额不足");
        BigDecimal price = decimal(row, "price");
        require(
            mode.equals("EXTENSION")
                || (price.compareTo(plan.minPrice()) >= 0 && price.compareTo(plan.maxPrice()) <= 0),
            "PRICE_RANGE",
            "某晚价格不在筛选区间");
        total = total.add(price.multiply(BigDecimal.valueOf(quantity)));
        lines.add(
            map(
                "inventoryId",
                row.get("id"),
                "roomTypeId",
                row.get("roomTypeId"),
                "code",
                code,
                "roomName",
                row.get("roomName"),
                "date",
                str(row, "stayDate"),
                "quantity",
                quantity,
                "price",
                price,
                "available",
                available,
                "saleVersion",
                row.get("version")));
      }
    }
    require(capacity >= plan.guestIds().size(), "CAPACITY", "房型总容量不足以容纳来宾");
    require(mode.equals("EXTENSION") || total.compareTo(plan.budget()) <= 0, "BUDGET", "整单价格超过预算");
    BigDecimal numerator =
        new BigDecimal("400")
            .multiply(plan.budget().subtract(total))
            .multiply(plan.maxDistance())
            .add(
                new BigDecimal("300")
                    .multiply(plan.maxDistance().subtract(distance))
                    .multiply(plan.budget()))
            .add(
                new BigDecimal("3")
                    .multiply(evaluation)
                    .multiply(plan.budget())
                    .multiply(plan.maxDistance()));
    BigDecimal score =
        numerator.divide(
            BigDecimal.TEN.multiply(plan.budget()).multiply(plan.maxDistance()),
            12,
            RoundingMode.HALF_UP);
    return map(
        "hotelId",
        hotel.get("id"),
        "hotelName",
        hotel.get("name"),
        "address",
        hotel.get("address"),
        "description",
        hotel.get("description"),
        "hotelVersion",
        hotel.get("version"),
        "distanceVersion",
        hotel.get("distanceVersion"),
        "distance",
        distance,
        "evaluation",
        evaluation,
        "plan",
        plan.view(),
        "lines",
        lines,
        "total",
        total,
        "score",
        score,
        "scoreNumerator",
        numerator,
        "rule",
        map(
            "version",
            hotel.get("ruleVersion"),
            "cancellationText",
            hotel.get("cancellationText"),
            "earlyCheckoutText",
            hotel.get("earlyCheckoutText"),
            "noShowText",
            hotel.get("noShowText"),
            "cancelDeadline",
            clock.cutoff(plan.start()).toString()));
  }
}
