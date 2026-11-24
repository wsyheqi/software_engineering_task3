package cn.edu.campus.hospitality.masterdata;

import static cn.edu.campus.hospitality.shared.Data.decimal;
import static cn.edu.campus.hospitality.shared.Data.id;
import static cn.edu.campus.hospitality.shared.Data.map;
import static cn.edu.campus.hospitality.shared.Data.str;

import cn.edu.campus.hospitality.booking.BookingService;
import cn.edu.campus.hospitality.inventory.InventoryService;
import cn.edu.campus.hospitality.notification.NotificationService;
import cn.edu.campus.hospitality.shared.BusinessClock;
import cn.edu.campus.hospitality.shared.Data;
import cn.edu.campus.hospitality.shared.Db;
import cn.edu.campus.hospitality.shared.Json;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Seeds example users, hotels, rates, and orders for the local demonstration. */
@Component
@ConditionalOnProperty(name = "app.demo", havingValue = "true", matchIfMissing = true)
public class DemoData implements CommandLineRunner {
  private final Db db;
  private final Json json;
  private final BusinessClock clock;
  private final PasswordEncoder encoder;
  private final MasterDataService master;
  private final BookingService booking;
  private final InventoryService inventory;
  private final NotificationService notices;

  /** Creates the demo initializer with the same inventory and booking services used at runtime. */
  public DemoData(
      Db db,
      Json json,
      BusinessClock clock,
      PasswordEncoder encoder,
      MasterDataService master,
      BookingService booking,
      InventoryService inventory,
      NotificationService notices) {
    this.db = db;
    this.json = json;
    this.clock = clock;
    this.encoder = encoder;
    this.master = master;
    this.booking = booking;
    this.inventory = inventory;
    this.notices = notices;
  }

  @Override
  @Transactional
  public void run(String... args) {
    if (db.count("SELECT COUNT(*) FROM app_user") > 0) {
      return;
    }
    user("u-admin", "admin", "张老师", "ADMIN", null, null);
    user("u-rec", "reception", "李明", "RECEPTION", null, null);
    user("u-rec2", "reception2", "王婷", "RECEPTION", null, null);
    user("u-hotel", "hotel", "学府宾馆 · 前台", "HOTEL", "h1", null);
    user("u-hotel2", "hotel2", "湖畔商务 · 前台", "HOTEL", "h2", null);
    guest("g1", "u-rec", "陈浩", "13812345678", "CAMPUS-G001");
    guest("g2", "u-rec", "冯琳", "13912345678", "CAMPUS-G002");
    guest("g3", "u-rec2", "林悦", "13712345678", "CAMPUS-G003");
    guest("g4", "u-rec", "周宇", "13612345678", "CAMPUS-G004");
    user("u-guest", "guest", "陈浩", "GUEST", null, "g1");
    user("u-guest2", "guest2", "冯琳", "GUEST", null, "g2");
    db.insert(
        "reception_site", map("id", "site1", "name", "仙林校区 · 行政楼", "address", "南京市栖霞区仙林大道 163 号"));
    db.insert(
        "reception_site", map("id", "site2", "name", "鼓楼校区 · 接待中心", "address", "南京市鼓楼区汉口路 22 号"));
    hotel("h1", "学府宾馆", 92, true, "3.2", "南京市栖霞区文苑路 18 号", "校园周边 · 安静舒适 · 适合学术接待", 300, 220, 520);
    hotel(
        "h2", "湖畔商务酒店", 95, true, "2.1", "南京市栖霞区仙林湖路 28 号", "湖景环境 · 商务设施完善 · 出行方便", 360, 260, 600);
    hotel("h3", "城市会议酒店", 88, true, "5.8", "南京市栖霞区学海路 66 号", "会议配套 · 多种房型 · 团队接待", 280, 200, 500);
    hotel("h4", "城西旅居酒店", 69, true, "8.5", "南京市栖霞区学衡路 36 号", "学校评价未达推荐门槛", 240, 180, 420);
    hotel("h5", "新园酒店", null, false, "4.6", "南京市栖霞区文澜路 9 号", "等待学校完成质量审核", 260, 190, 460);
    db.update(
        "UPDATE fee_rule SET early_checkout_text=? WHERE hotel_id='h1'",
        "提前退房只计实际已住夜晚；双方约定的50元处理费须由酒店提出、接待核对并单列，不能混作住宿费用。");
    LocalDate today = clock.today();
    final String future =
        seedOrder(today.plusDays(3), today.plusDays(6), 1, List.of("g1", "g2"), "DOUBLE");
    String current =
        seedOrder(today.minusDays(2), today.plusDays(1), 2, List.of("g1", "g2"), "DOUBLE");
    db.insert(
        "guest_checkin",
        map(
            "id",
            id(),
            "order_id",
            current,
            "guest_id",
            "g1",
            "arrived_at",
            today.minusDays(2).atTime(14, 30)));
    db.insert(
        "guest_checkin",
        map(
            "id",
            id(),
            "order_id",
            current,
            "guest_id",
            "g2",
            "arrived_at",
            today.minusDays(2).atTime(16, 20)));
    booking.transition(map("id", current, "status", "CONFIRMED"), "CHECKED_IN");
    actual(current, today.minusDays(2), 2);
    actual(current, today.minusDays(1), 2);
    String previous =
        seedOrder(today.minusDays(10), today.minusDays(7), 1, List.of("g4"), "SINGLE");
    db.insert(
        "guest_checkin",
        map(
            "id",
            id(),
            "order_id",
            previous,
            "guest_id",
            "g4",
            "arrived_at",
            today.minusDays(10).atTime(15, 0),
            "departed_at",
            today.minusDays(8).atTime(11, 0)));
    booking.transition(map("id", previous, "status", "CONFIRMED"), "CHECKED_IN");
    actual(previous, today.minusDays(10), 1);
    actual(previous, today.minusDays(9), 1);
    inventory.release(previous, today.minusDays(8), true);
    db.update(
        "UPDATE booking_order SET actual_departure=? WHERE id=?", today.minusDays(8), previous);
    booking.transition(map("id", previous, "status", "CHECKED_IN"), "CHECKED_OUT");
    booking.fee(
        previous,
        "EXTRA",
        new BigDecimal("50"),
        "demo-extra",
        "PENDING",
        "依据本单确认时展示的50元提前退房处理费约定，待负责接待人员核对",
        "u-hotel");
    notices.enqueue(
        "welcome",
        "u-rec",
        "IN_APP",
        future,
        "h1",
        "欢迎使用学宾接待",
        "示例数据已准备好。可以录入住宿需求、查询酒店，并按角色完成预订与入住。所有示例信息均为演示用途。");
  }

  private void user(
      String id, String username, String name, String role, String hotel, String guest) {
    db.insert(
        "app_user",
        map(
            "id",
            id,
            "username",
            username,
            "display_name",
            name,
            "password_hash",
            encoder.encode("Demo@123456"),
            "role",
            role,
            "hotel_id",
            hotel,
            "guest_id",
            guest,
            "email",
            username + "@example.edu.cn"));
  }

  private void guest(String id, String owner, String name, String phone, String ref) {
    db.insert(
        "guest",
        map("id", id, "owner_id", owner, "name", name, "phone", phone, "identity_ref", ref));
  }

  private void hotel(
      String id,
      String name,
      Integer score,
      boolean approved,
      String distance,
      String address,
      String description,
      int doublePrice,
      int singlePrice,
      int suitePrice) {
    db.insert(
        "hotel",
        map(
            "id",
            id,
            "name",
            name,
            "city",
            "南京",
            "address",
            address,
            "description",
            description,
            "audited",
            approved,
            "evaluation",
            score));
    master.defaultRule(id);
    db.insert(
        "hotel_site_distance",
        map("hotel_id", id, "site_id", "site1", "distance_km", new BigDecimal(distance)));
    db.insert(
        "hotel_site_distance",
        map(
            "hotel_id",
            id,
            "site_id",
            "site2",
            "distance_km",
            new BigDecimal(distance).add(new BigDecimal("12"))));
    String[] codes = {"DOUBLE", "SINGLE", "SUITE"};
    String[] names = {"舒适双床房", "雅致单人房", "商务套房"};
    int[] prices = {doublePrice, singlePrice, suitePrice};
    int[] capacities = {2, 1, 3};
    int[] quotas = {12, 8, 4};
    for (int i = 0; i < codes.length; i++) {
      String room = id + "-" + codes[i];
      db.insert(
          "room_type",
          map(
              "id",
              room,
              "hotel_id",
              id,
              "code",
              codes[i],
              "name",
              names[i],
              "capacity",
              capacities[i]));
      for (LocalDate day = clock.today().minusDays(30);
          day.isBefore(clock.today().plusDays(181));
          day = day.plusDays(1)) {
        db.insert(
            "daily_inventory",
            map(
                "id",
                Data.id(),
                "hotel_id",
                id,
                "room_type_id",
                room,
                "stay_date",
                day,
                "price",
                prices[i],
                "quota",
                quotas[i]));
      }
    }
  }

  private String seedOrder(
      LocalDate start, LocalDate end, int quantity, List<String> guests, String code) {
    var hotel = db.one("SELECT * FROM hotel WHERE id='h1'");
    var rule = db.one("SELECT * FROM fee_rule WHERE hotel_id='h1'");
    var plan =
        map(
            "city",
            "南京",
            "siteId",
            "site1",
            "startDate",
            start.toString(),
            "endDate",
            end.toString(),
            "budget",
            5000,
            "maxDistance",
            20,
            "minPrice",
            0,
            "maxPrice",
            1000,
            "items",
            List.of(map("code", code, "quantity", quantity)),
            "guestIds",
            guests,
            "special",
            "学术交流接待");
    String demand = id();
    db.insert(
        "stay_demand",
        map(
            "id",
            demand,
            "owner_id",
            "u-rec",
            "plan_json",
            json.write(plan),
            "version",
            1,
            "created_at",
            clock.now().minusDays(12)));
    List<Map<String, Object>> lines = new ArrayList<>();
    BigDecimal total = BigDecimal.ZERO;
    for (var r : inventory.range(start, end, "h1", false)) {
      if (code.equals(str(r, "code"))) {
        lines.add(
            map(
                "inventoryId",
                r.get("id"),
                "roomTypeId",
                r.get("roomTypeId"),
                "code",
                code,
                "roomName",
                r.get("roomName"),
                "date",
                str(r, "stayDate"),
                "quantity",
                quantity,
                "price",
                r.get("price")));
        total = total.add(decimal(r, "price").multiply(BigDecimal.valueOf(quantity)));
      }
    }
    return booking.createConfirmed(
        "u-rec",
        demand,
        map(
            "hotelId",
            "h1",
            "hotelName",
            hotel.get("name"),
            "plan",
            plan,
            "lines",
            lines,
            "total",
            total,
            "rule",
            map(
                "version",
                rule.get("version"),
                "cancellationText",
                rule.get("cancellationText"),
                "earlyCheckoutText",
                rule.get("earlyCheckoutText"),
                "noShowText",
                rule.get("noShowText"),
                "cancelDeadline",
                clock.cutoff(start).toString())),
        null,
        true);
  }

  private void actual(String order, LocalDate date, int quantity) {
    var n = db.one("SELECT * FROM order_night WHERE order_id=? AND stay_date=?", order, date);
    String id = id();
    db.insert(
        "actual_room_night",
        map(
            "id",
            id,
            "order_id",
            order,
            "item_id",
            n.get("itemId"),
            "stay_date",
            date,
            "quantity",
            quantity,
            "price",
            n.get("price"),
            "recorded_by",
            "u-hotel"));
    booking.fee(
        order,
        "LODGING",
        decimal(n, "price").multiply(BigDecimal.valueOf(quantity)),
        "actual:" + id,
        "CONFIRMED",
        date + " 实际住宿",
        "u-hotel");
  }
}
