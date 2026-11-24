package cn.edu.campus.hospitality;

import static cn.edu.campus.hospitality.shared.Data.date;
import static cn.edu.campus.hospitality.shared.Data.decimal;
import static cn.edu.campus.hospitality.shared.Data.id;
import static cn.edu.campus.hospitality.shared.Data.integer;
import static cn.edu.campus.hospitality.shared.Data.map;
import static cn.edu.campus.hospitality.shared.Data.object;
import static cn.edu.campus.hospitality.shared.Data.objects;
import static cn.edu.campus.hospitality.shared.Data.str;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.edu.campus.hospitality.booking.BookingService;
import cn.edu.campus.hospitality.identity.Access;
import cn.edu.campus.hospitality.identity.Actor;
import cn.edu.campus.hospitality.masterdata.DemoData;
import cn.edu.campus.hospitality.masterdata.MasterDataService;
import cn.edu.campus.hospitality.notification.NotificationService;
import cn.edu.campus.hospitality.reporting.ReportingService;
import cn.edu.campus.hospitality.search.SearchService;
import cn.edu.campus.hospitality.shared.BusinessException;
import cn.edu.campus.hospitality.shared.CommandService;
import cn.edu.campus.hospitality.shared.Db;
import cn.edu.campus.hospitality.shared.Json;
import cn.edu.campus.hospitality.stay.StayService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:business;MODE=MySQL;DATABASE_TO_LOWER=TRUE;"
          + "DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
      "app.scheduling=false",
      "app.demo=true",
      "logging.level.root=WARN"
    })
@AutoConfigureMockMvc
@Import(BusinessIntegrationTest.ClockConfig.class)
class BusinessIntegrationTest {
  @Autowired Db db;
  @Autowired Json json;
  @Autowired Access access;
  @Autowired CommandService commands;
  @Autowired SearchService search;
  @Autowired BookingService booking;
  @Autowired DemoData seed;
  @Autowired MutableClock clock;
  @Autowired ReportingService reports;
  @Autowired NotificationService notices;
  @Autowired StayService stays;
  @Autowired MockMvc mvc;
  @Autowired MasterDataService master;
  Actor rec;
  Actor rec2;
  Actor hotel;
  Actor hotel2;
  Actor admin;
  LocalDate start;
  LocalDate end;

  @TestConfiguration
  static class ClockConfig {
    @Bean
    @Primary
    MutableClock testClock() {
      return new MutableClock();
    }
  }

  static class MutableClock extends Clock {
    private volatile Instant now =
        LocalDateTime.of(2026, 11, 10, 13, 0).atZone(ZoneId.of("Asia/Shanghai")).toInstant();

    void at(LocalDateTime value) {
      now = value.atZone(getZone()).toInstant();
    }

    @Override
    public ZoneId getZone() {
      return ZoneId.of("Asia/Shanghai");
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return Clock.fixed(now, zone);
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  @BeforeEach
  void reset() {
    clock.at(LocalDateTime.of(2026, 11, 10, 13, 0));
    for (String table :
        List.of(
            "notification",
            "notification_task",
            "work_item",
            "audit_log",
            "operation_request",
            "relocation_case",
            "hotel_closure",
            "extension_request",
            "special_change_approval",
            "fee_record",
            "actual_room_night",
            "guest_checkin",
            "order_revision",
            "order_night",
            "order_item",
            "order_guest",
            "booking_order",
            "quote_snapshot",
            "stay_demand",
            "inventory_block",
            "daily_inventory",
            "room_type",
            "fee_rule",
            "hotel_site_distance",
            "hotel",
            "reception_site",
            "guest",
            "app_user")) {
      db.update("DELETE FROM " + table);
    }
    seed.run();
    rec = access.byUsername("reception");
    rec2 = access.byUsername("reception2");
    hotel = access.byUsername("hotel");
    hotel2 = access.byUsername("hotel2");
    admin = access.byUsername("admin");
    start = LocalDate.of(2026, 11, 22);
    end = start.plusDays(3);
  }

  Map<String, Object> plan(List<String> guests, List<Map<String, Object>> items) {
    return map(
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
        2000,
        "items",
        items,
        "guestIds",
        guests,
        "special",
        "");
  }

  Map<String, Object> quote(Actor actor, String hotelId, List<Map<String, Object>> items) {
    var demand =
        search.saveDemand(
            actor, plan(actor.id().equals(rec2.id()) ? List.of("g3") : List.of("g1", "g2"), items));
    return choose(search.search(actor, map("demandId", demand.get("id"))), hotelId);
  }

  Map<String, Object> quote() {
    return quote(rec, "h1", List.of(map("code", "DOUBLE", "quantity", 1)));
  }

  Map<String, Object> choose(List<Map<String, Object>> options, String hotelId) {
    return options.stream()
        .filter(q -> str(object(q, "snapshot"), "hotelId").equals(hotelId))
        .findFirst()
        .orElseThrow();
  }

  Map<String, Object> cmd(Actor actor, String action, Map<String, Object> body) {
    return commands.submit(actor, action, body, id());
  }

  String book(Map<String, Object> q) {
    var r = cmd(rec, "BOOK", map("quoteId", q.get("quoteId"), "rulesConfirmed", true));
    assertThat(str(r, "status")).isEqualTo("SUCCEEDED");
    return str(object(r, "data"), "orderId");
  }

  int occupied(String hotelId) {
    return (int)
        db.count(
            "SELECT COALESCE(SUM(occupied),0) FROM daily_inventory WHERE hotel_id=? AND"
                + " stay_date>=? AND stay_date<?",
            hotelId,
            start,
            end);
  }

  @Test
  void allNightsAndMultiRoomBookingAreAtomic() {
    var q =
        quote(
            rec,
            "h1",
            List.of(map("code", "DOUBLE", "quantity", 1), map("code", "SINGLE", "quantity", 1)));
    db.update(
        "UPDATE daily_inventory SET quota=0,version=version+1 WHERE hotel_id='h1' AND"
            + " room_type_id='h1-SINGLE' AND stay_date=?",
        end.minusDays(1));
    var r = cmd(rec, "BOOK", map("quoteId", q.get("quoteId"), "rulesConfirmed", true));
    assertThat(str(r, "businessCode")).isEqualTo("INSUFFICIENT_INVENTORY");
    assertThat(occupied("h1")).isZero();
    assertThat(db.count("SELECT COUNT(*) FROM booking_order")).isEqualTo(3);
  }

  @Test
  void changedPriceOrStillSufficientQuotaRequiresExplicitReconfirmation() {
    var q = quote();
    db.update(
        "UPDATE daily_inventory SET quota=quota-1,version=version+1 WHERE room_type_id='h1-DOUBLE'"
            + " AND stay_date=?",
        start);
    String key = id();
    var body = map("quoteId", q.get("quoteId"), "rulesConfirmed", true);
    var r = commands.submit(rec, "BOOK", body, key);
    assertThat(str(r, "status")).isEqualTo("NEEDS_RECONFIRMATION");
    assertThat(object(r, "data")).containsKey("latest");
    assertThat(occupied("h1")).isZero();
    assertThat(commands.submit(rec, "BOOK", body, key)).isEqualTo(r);
    var fresh = quote();
    String order = book(fresh);
    db.update(
        "UPDATE daily_inventory SET price=999,version=version+1 WHERE room_type_id='h1-DOUBLE' AND"
            + " stay_date=?",
        start);
    assertThat(
            db.one("SELECT price FROM order_night WHERE order_id=? AND stay_date=?", order, start)
                .get("price"))
        .isEqualTo(new BigDecimal("300.00"));
  }

  @Test
  void duplicateConcurrentRequestCreatesOnlyOneOrder() throws Exception {
    var q = quote();
    String key = id();
    var body = map("quoteId", q.get("quoteId"), "rulesConfirmed", true);
    ExecutorService pool = Executors.newFixedThreadPool(6);
    try {
      var jobs = new ArrayList<Callable<Map<String, Object>>>();
      for (int i = 0; i < 6; i++) {
        jobs.add(() -> commands.submit(rec, "BOOK", body, key));
      }
      var results = pool.invokeAll(jobs);
      Set<String> ids = new HashSet<>();
      for (var f : results) {
        var r = f.get();
        assertThat(str(r, "status")).isEqualTo("SUCCEEDED");
        ids.add(str(object(r, "data"), "orderId"));
      }
      assertThat(ids).hasSize(1);
      assertThat(db.count("SELECT COUNT(*) FROM booking_order")).isEqualTo(4);
      assertThat(occupied("h1")).isEqualTo(3);
    } finally {
      pool.shutdownNow();
    }
    assertThatThrownBy(
            () ->
                commands.submit(
                    rec, "BOOK", map("quoteId", "different", "rulesConfirmed", true), key))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("不同操作或参数");
  }

  @Test
  void finalRoomCanOnlyBeBookedOnce() throws Exception {
    db.update(
        "UPDATE daily_inventory SET quota=1,version=version+1 WHERE room_type_id='h1-DOUBLE' AND"
            + " stay_date>=? AND stay_date<?",
        start,
        end);
    var qa = quote();
    var qb = quote(rec2, "h1", List.of(map("code", "DOUBLE", "quantity", 1)));
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch go = new CountDownLatch(1);
    try {
      var a =
          pool.submit(
              () -> {
                go.await();
                return cmd(rec, "BOOK", map("quoteId", qa.get("quoteId"), "rulesConfirmed", true));
              });
      var b =
          pool.submit(
              () -> {
                go.await();
                return cmd(rec2, "BOOK", map("quoteId", qb.get("quoteId"), "rulesConfirmed", true));
              });
      go.countDown();
      var r = List.of(a.get(), b.get());
      assertThat(r.stream().filter(x -> str(x, "status").equals("SUCCEEDED")).count()).isEqualTo(1);
      assertThat(occupied("h1")).isEqualTo(3);
    } finally {
      pool.shutdownNow();
    }
  }

  String twoPriceOrder() {
    db.update(
        "UPDATE daily_inventory SET price=200 WHERE room_type_id='h1-DOUBLE' AND stay_date>=? AND"
            + " stay_date<?",
        start,
        end);
    db.update(
        "UPDATE daily_inventory SET price=300 WHERE room_type_id='h1-SINGLE' AND stay_date>=? AND"
            + " stay_date<?",
        start,
        end);
    return book(
        quote(
            rec,
            "h1",
            List.of(map("code", "DOUBLE", "quantity", 1), map("code", "SINGLE", "quantity", 1))));
  }

  @Test
  void cancellationAtExactCutoffUsesLockedFirstNightAndReleasesOnce() {
    String order = twoPriceOrder();
    clock.at(start.minusDays(1).atTime(18, 0));
    db.update(
        "UPDATE daily_inventory SET price=999,version=version+1 WHERE hotel_id='h1' AND"
            + " stay_date=?",
        start);
    String key = id();
    var r = commands.submit(rec, "CANCEL", map("orderId", order), key);
    assertThat(decimal(object(r, "data"), "cancellationFee")).isEqualByComparingTo("500");
    assertThat(occupied("h1")).isZero();
    assertThat(commands.submit(rec, "CANCEL", map("orderId", order), key)).isEqualTo(r);
    assertThat(db.count("SELECT COUNT(*) FROM fee_record WHERE source_key=?", "cancel:" + order))
        .isEqualTo(1);
  }

  @Test
  void cancellationOneSecondBeforeCutoffIsFree() {
    String order = twoPriceOrder();
    clock.at(start.minusDays(1).atTime(17, 59, 59));
    var r = cmd(rec, "CANCEL", map("orderId", order));
    assertThat(decimal(object(r, "data"), "cancellationFee")).isZero();
  }

  @Test
  void failedChangeKeepsOriginalOrderAndAllocation() {
    String order = book(quote());
    var q =
        choose(
            search.search(
                rec,
                map(
                    "mode",
                    "CHANGE",
                    "orderId",
                    order,
                    "plan",
                    plan(List.of("g1", "g2"), List.of(map("code", "DOUBLE", "quantity", 1))))),
            "h2");
    var original = db.one("SELECT * FROM booking_order WHERE id=?", order);
    db.update(
        "UPDATE daily_inventory SET quota=0,version=version+1 WHERE room_type_id='h2-DOUBLE' AND"
            + " stay_date=?",
        end.minusDays(1));
    var r =
        cmd(
            rec,
            "CHANGE",
            map("orderId", order, "quoteId", q.get("quoteId"), "differenceConfirmed", true));
    assertThat(str(r, "status")).isEqualTo("REJECTED");
    assertThat(db.one("SELECT * FROM booking_order WHERE id=?", order)).isEqualTo(original);
    assertThat(occupied("h1")).isEqualTo(3);
    assertThat(occupied("h2")).isZero();
  }

  @Test
  void specialChangeRequiresBothApprovalsAndCannotRestoreTerminalOrder() {
    String order = book(quote());
    var q = choose(search.search(rec, map("mode", "CHANGE", "orderId", order)), "h2");
    clock.at(start.minusDays(1).atTime(18, 0));
    var body = map("orderId", order, "quoteId", q.get("quoteId"), "differenceConfirmed", true);
    assertThat(str(cmd(rec, "CHANGE", body), "businessCode"))
        .isEqualTo("SPECIAL_APPROVAL_REQUIRED");
    var request =
        cmd(
            rec,
            "SPECIAL_REQUEST",
            map("orderId", order, "quoteId", q.get("quoteId"), "note", "接待调整"));
    String approval = str(object(request, "data"), "approvalId");
    cmd(admin, "SPECIAL_CONFIRM", map("id", approval));
    body.put("approvalId", approval);
    assertThat(str(cmd(rec, "CHANGE", body), "businessCode"))
        .isEqualTo("SPECIAL_APPROVAL_REQUIRED");
    cmd(hotel, "SPECIAL_CONFIRM", map("id", approval));
    assertThat(str(cmd(rec, "CHANGE", body), "status")).isEqualTo("SUCCEEDED");
    assertThat(str(db.one("SELECT hotel_id FROM booking_order WHERE id=?", order), "hotelId"))
        .isEqualTo("h2");
    cmd(rec, "CANCEL", map("orderId", order));
    assertThat(str(cmd(rec, "CHANGE", body), "businessCode")).isEqualTo("STATE_CONFLICT");
  }

  @Test
  void checkinsArePerPersonAndCrossHotelIsDenied() {
    String order = book(quote());
    clock.at(start.atTime(14, 0));
    cmd(hotel, "CHECK_IN", map("orderId", order, "guestId", "g1"));
    assertThat(db.count("SELECT COUNT(*) FROM guest_checkin WHERE order_id=?", order)).isEqualTo(1);
    cmd(hotel, "CHECK_IN", map("orderId", order, "guestId", "g1"));
    assertThat(db.count("SELECT COUNT(*) FROM guest_checkin WHERE order_id=?", order)).isEqualTo(1);
    assertThat(str(cmd(hotel2, "CHECK_IN", map("orderId", order, "guestId", "g2")), "businessCode"))
        .isEqualTo("FORBIDDEN");
    cmd(hotel, "CHECK_IN", map("orderId", order, "guestId", "g2"));
    assertThat(db.count("SELECT COUNT(*) FROM guest_checkin WHERE order_id=?", order)).isEqualTo(2);
    assertThat(str(db.one("SELECT status FROM booking_order WHERE id=?", order), "status"))
        .isEqualTo("CHECKED_IN");
  }

  @Test
  void remindersDoNotAutomaticallyMarkNoShowAndLateArrivalsNeedNewBooking() {
    String order = book(quote());
    clock.at(start.plusDays(1).atTime(0, 0));
    stays.arrivalReminders();
    assertThat(str(db.one("SELECT status FROM booking_order WHERE id=?", order), "status"))
        .isEqualTo("CONFIRMED");
    assertThat(str(cmd(hotel, "NO_SHOW", map("orderId", order)), "businessCode"))
        .isEqualTo("HOLD_ACTIVE");
    clock.at(start.plusDays(1).atTime(12, 0, 1));
    assertThat(str(cmd(hotel, "NO_SHOW", map("orderId", order)), "status")).isEqualTo("SUCCEEDED");
    assertThat(str(cmd(hotel, "CHECK_IN", map("orderId", order, "guestId", "g1")), "businessCode"))
        .isEqualTo("STATE_CONFLICT");
    assertThat(
            db.count(
                "SELECT COUNT(*) FROM notification_task WHERE event_key=?", "no-show:" + order))
        .isEqualTo(1);
  }

  @Test
  void relocationFailureRetainsOriginalAndSuccessIsAtomic() {
    String order = book(quote());
    cmd(
        hotel,
        "CLOSURE",
        map(
            "hotelId",
            "h1",
            "startDate",
            start.toString(),
            "endDate",
            end.toString(),
            "reason",
            "设备维修停业"));
    var c = db.one("SELECT * FROM relocation_case WHERE order_id=?", order);
    var q = choose(search.search(rec, map("mode", "RELOCATION", "orderId", order)), "h2");
    db.update(
        "UPDATE daily_inventory SET quota=0,version=version+1 WHERE room_type_id='h2-DOUBLE' AND"
            + " stay_date=?",
        start);
    var bad =
        cmd(
            rec,
            "RELOCATE",
            map("id", c.get("id"), "quoteId", q.get("quoteId"), "confirmed", true));
    assertThat(str(bad, "status")).isEqualTo("REJECTED");
    assertThat(occupied("h1")).isEqualTo(3);
    assertThat(str(db.one("SELECT status FROM booking_order WHERE id=?", order), "status"))
        .isEqualTo("RELOCATING");
    var good = choose(search.search(rec, map("mode", "RELOCATION", "orderId", order)), "h3");
    String key = id();
    var body = map("id", c.get("id"), "quoteId", good.get("quoteId"), "confirmed", true);
    var r = commands.submit(rec, "RELOCATE", body, key);
    assertThat(str(r, "status")).isEqualTo("SUCCEEDED");
    assertThat(occupied("h1")).isZero();
    assertThat(
            db.count(
                "SELECT COUNT(*) FROM daily_inventory WHERE hotel_id='h1' AND stay_date>=? AND"
                    + " stay_date<? AND stop_sale=TRUE",
                start,
                end))
        .isEqualTo(9);
    assertThat(commands.submit(rec, "RELOCATE", body, key)).isEqualTo(r);
    assertThat(db.count("SELECT COUNT(*) FROM booking_order WHERE source_order_id=?", order))
        .isEqualTo(1);
  }

  @Test
  void occupiedClosurePreservesActualFourNightsAndOnlyTransfersRemainingTwo() {
    var order = db.one("SELECT * FROM booking_order WHERE status='CHECKED_IN'");
    String id = str(order, "id");
    LocalDate today = LocalDate.of(2026, 11, 10);
    cmd(
        hotel,
        "CLOSURE",
        map(
            "hotelId",
            "h1",
            "startDate",
            today.toString(),
            "endDate",
            today.plusDays(1).toString(),
            "reason",
            "临时停业"));
    var c = db.one("SELECT * FROM relocation_case WHERE order_id=?", id);
    var q = choose(search.search(rec, map("mode", "RELOCATION", "orderId", id)), "h2");
    var r =
        cmd(
            rec,
            "RELOCATE",
            map("id", c.get("id"), "quoteId", q.get("quoteId"), "confirmed", true));
    assertThat(str(r, "status")).isEqualTo("SUCCEEDED");
    assertThat(
            str(
                db.one("SELECT start_date FROM booking_order WHERE source_order_id=?", id),
                "startDate"))
        .isEqualTo(today.toString());
    var report = reports.report(admin, "actual", today.minusDays(2), today, "");
    assertThat(integer(report, "actualNights")).isEqualTo(4);
    assertThat(integer(report, "futureNights")).isEqualTo(2);
    assertThat(decimal(report, "actualCost")).isEqualByComparingTo("1200");
    notices.deliver();
    assertThat(
            db.count(
                "SELECT COUNT(*) FROM work_item WHERE kind='NOTIFICATION_FAILURE' AND order_id=?",
                id))
        .isGreaterThan(0);
    assertThat(str(db.one("SELECT status FROM booking_order WHERE id=?", id), "status"))
        .isEqualTo("CLOSED");
  }

  @Test
  void guestDtoOmitsProtocolPricesAndOtherGuestsAndCsrfIsEnforced() throws Exception {
    String body =
        mvc.perform(get("/api/me/stays").with(user("guest").roles("GUEST")))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    assertThat(body)
        .contains("陈浩", "138****5678")
        .doesNotContain("冯琳", "13912345678", "total", "price", "ruleJson");
    mvc.perform(get("/api/bookings").with(user("guest").roles("GUEST")))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/bookings")
                .with(user("reception").roles("RECEPTION"))
                .header("Idempotency-Key", id())
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isForbidden());
    assertThat(db.count("SELECT COUNT(*) FROM operation_request")).isZero();
    String current = str(db.one("SELECT id FROM booking_order WHERE status='CHECKED_IN'"), "id");
    String adminBody =
        mvc.perform(get("/api/bookings/" + current).with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    assertThat(adminBody).doesNotContain("13812345678", "13912345678").contains("138****5678");
  }

  @Test
  void extensionUsesApplicationPriceAndFailureLeavesOriginalStay() {
    var order = db.one("SELECT * FROM booking_order WHERE status='CHECKED_IN'");
    String oid = str(order, "id");
    LocalDate extra = date(order, "endDate");
    db.update(
        "UPDATE daily_inventory SET price=700,version=version+1 WHERE room_type_id='h1-DOUBLE' AND"
            + " stay_date=?",
        extra);
    var q =
        choose(
            search.search(
                hotel,
                map("mode", "EXTENSION", "orderId", oid, "endDate", extra.plusDays(1).toString())),
            "h1");
    var proposal =
        cmd(hotel, "EXTENSION_PROPOSE", map("orderId", oid, "quoteId", q.get("quoteId")));
    String extension = str(object(proposal, "data"), "extensionId");
    assertThat(
            db.count(
                "SELECT occupied FROM daily_inventory WHERE room_type_id='h1-DOUBLE' AND"
                    + " stay_date=?",
                extra))
        .isZero();
    var result = cmd(rec, "EXTENSION_CONFIRM", map("id", extension, "priceConfirmed", true));
    assertThat(str(result, "status")).isEqualTo("SUCCEEDED");
    assertThat(
            db.count(
                "SELECT occupied FROM daily_inventory WHERE room_type_id='h1-DOUBLE' AND"
                    + " stay_date=?",
                extra))
        .isEqualTo(2);
    assertThat(
            db.one("SELECT price FROM order_night WHERE order_id=? AND stay_date=?", oid, extra)
                .get("price"))
        .isEqualTo(new BigDecimal("700.00"));
    assertThat(
            db.one(
                    "SELECT price FROM order_night WHERE order_id=? AND stay_date=?",
                    oid,
                    extra.minusDays(1))
                .get("price"))
        .isEqualTo(new BigDecimal("300.00"));
    assertThat(
            str(
                cmd(rec, "EXTENSION_CONFIRM", map("id", extension, "priceConfirmed", true)),
                "businessCode"))
        .isEqualTo("STATE_CONFLICT");
    var fresh = db.one("SELECT * FROM booking_order WHERE id=?", oid);
    var next =
        choose(
            search.search(
                hotel,
                map("mode", "EXTENSION", "orderId", oid, "endDate", extra.plusDays(2).toString())),
            "h1");
    var request =
        cmd(hotel, "EXTENSION_PROPOSE", map("orderId", oid, "quoteId", next.get("quoteId")));
    db.update(
        "UPDATE daily_inventory SET quota=0,version=version+1 WHERE room_type_id='h1-DOUBLE' AND"
            + " stay_date=?",
        extra.plusDays(1));
    assertThat(
            str(
                cmd(
                    rec,
                    "EXTENSION_CONFIRM",
                    map("id", object(request, "data").get("extensionId"), "priceConfirmed", true)),
                "status"))
        .isEqualTo("REJECTED");
    assertThat(db.one("SELECT * FROM booking_order WHERE id=?", oid)).isEqualTo(fresh);
  }

  @Test
  void earlyCheckoutReleasesOnlyFutureAndExtraFeeIsSeparate() {
    var order = db.one("SELECT * FROM booking_order WHERE status='CHECKED_IN'");
    String oid = str(order, "id");
    LocalDate today = LocalDate.of(2026, 11, 10);
    var r = cmd(hotel, "CHECK_OUT", map("orderId", oid, "departureDate", today.toString()));
    assertThat(str(r, "status")).isEqualTo("SUCCEEDED");
    assertThat(
            db.count(
                "SELECT occupied FROM daily_inventory WHERE room_type_id='h1-DOUBLE' AND"
                    + " stay_date=?",
                today))
        .isZero();
    assertThat(
            db.count(
                "SELECT COALESCE(SUM(quantity),0) FROM actual_room_night WHERE order_id=?", oid))
        .isEqualTo(4);
    cmd(
        hotel,
        "FEE_PROPOSE",
        map(
            "orderId",
            oid,
            "category",
            "EXTRA",
            "amount",
            50,
            "note",
            "按确认时的50元处理费约定",
            "ruleAcknowledged",
            true));
    var fee = db.one("SELECT * FROM fee_record WHERE order_id=? AND category='EXTRA'", oid);
    cmd(rec, "FEE_REVIEW", map("id", fee.get("id"), "accepted", false, "note", "请核实适用条件"));
    assertThat(str(db.one("SELECT status FROM fee_record WHERE id=?", fee.get("id")), "status"))
        .isEqualTo("DISPUTED");
    cmd(admin, "FEE_RESOLVE", map("id", fee.get("id"), "amount", 30, "note", "核实后协商减免20元"));
    var report = reports.report(admin, "actual", today.minusDays(2), today, "");
    assertThat(integer(report, "actualNights")).isEqualTo(4);
    assertThat(decimal(report, "actualCost")).isEqualByComparingTo("1200");
    assertThat(decimal(object(report, "fees"), "EXTRA")).isEqualByComparingTo("30");
  }

  @Test
  void repairConflictIsPendingAndCannotOverwriteHeldQuota() {
    final String order = book(quote());
    var r =
        cmd(
            hotel,
            "INVENTORY_BLOCK",
            map(
                "hotelId",
                "h1",
                "roomTypeId",
                "h1-DOUBLE",
                "startDate",
                start.toString(),
                "endDate",
                end.toString(),
                "quantity",
                12,
                "reason",
                "维修",
                "kind",
                "MAINTENANCE"));
    assertThat(str(object(r, "data"), "businessCode")).isEqualTo("BLOCK_PENDING");
    assertThat(occupied("h1")).isEqualTo(3);
    assertThat(
            db.count(
                "SELECT COALESCE(SUM(blocked),0) FROM daily_inventory WHERE"
                    + " room_type_id='h1-DOUBLE' AND stay_date>=? AND stay_date<?",
                start,
                end))
        .isZero();
    cmd(rec, "CANCEL", map("orderId", order));
    assertThat(
            str(cmd(hotel, "BLOCK_RETRY", map("id", object(r, "data").get("blockId"))), "status"))
        .isEqualTo("SUCCEEDED");
    assertThat(
            db.count(
                "SELECT COALESCE(SUM(blocked),0) FROM daily_inventory WHERE"
                    + " room_type_id='h1-DOUBLE' AND stay_date>=? AND stay_date<?",
                start,
                end))
        .isEqualTo(36);
  }

  @Test
  void normalizedRecommendationScoreMatchesBusinessExample() {
    end = start.plusDays(1);
    db.update("UPDATE hotel SET evaluation=80 WHERE id='h1'");
    db.update(
        "UPDATE hotel_site_distance SET distance_km=10 WHERE hotel_id='h1' AND site_id='site1'");
    db.update(
        "UPDATE daily_inventory SET price=500 WHERE room_type_id='h1-DOUBLE' AND stay_date=?",
        start);
    var p = plan(List.of("g1"), List.of(map("code", "DOUBLE", "quantity", 1)));
    p.put("budget", 1000);
    var d = search.saveDemand(rec, p);
    var q = choose(search.search(rec, map("demandId", d.get("id"))), "h1");
    assertThat(decimal(object(q, "snapshot"), "score")).isEqualByComparingTo("59");
  }

  @Test
  void newOrderDateStatisticsUseCreationDateRatherThanStayDate() {
    String order = book(quote());
    LocalDate today = LocalDate.of(2026, 11, 10);
    var r = reports.report(admin, "created", today, today, "");
    assertThat(objects(r, "orders")).anySatisfy(o -> assertThat(str(o, "id")).isEqualTo(order));
    assertThat(integer(r, "plannedNights")).isGreaterThanOrEqualTo(3);
  }

  @Test
  void durablePendingCommandCanBeRecoveredWithoutDuplicateAllocation() {
    var q = quote();
    String key = id();
    var body = map("quoteId", q.get("quoteId"), "rulesConfirmed", true);
    db.insert(
        "operation_request",
        map(
            "id",
            key,
            "actor_id",
            rec.id(),
            "action_type",
            "BOOK",
            "payload_hash",
            json.hash(body),
            "payload_json",
            json.write(body),
            "status",
            "PENDING",
            "created_at",
            LocalDateTime.of(2026, 11, 10, 12, 0),
            "updated_at",
            LocalDateTime.of(2026, 11, 10, 12, 0)));
    commands.recover();
    var result = commands.query(rec, key);
    assertThat(str(result, "status")).isEqualTo("SUCCEEDED");
    assertThat(commands.submit(rec, "BOOK", body, key)).isEqualTo(result);
    assertThat(occupied("h1")).isEqualTo(3);
  }

  @Test
  void malformedCommandTerminatesWithoutLeavingPendingOrPartialFacts() {
    var order = db.one("SELECT * FROM booking_order WHERE status='CHECKED_IN'");
    String key = id();
    var body =
        map("orderId", order.get("id"), "itemId", "invalid", "date", "not-a-date", "quantity", 1);
    long count = db.count("SELECT COUNT(*) FROM actual_room_night");
    var r = commands.submit(hotel, "ACTUAL_NIGHT", body, key);
    assertThat(str(r, "status")).isEqualTo("REJECTED");
    assertThat(str(r, "businessCode")).isEqualTo("VALIDATION");
    assertThat(db.count("SELECT COUNT(*) FROM operation_request WHERE status='PENDING'")).isZero();
    assertThat(db.count("SELECT COUNT(*) FROM actual_room_night")).isEqualTo(count);
    assertThat(commands.submit(hotel, "ACTUAL_NIGHT", body, key)).isEqualTo(r);
  }

  Map<String, Object> deletion(Actor actor, String kind, String target) {
    var row =
        db.one(
            "SELECT * FROM " + (kind.equals("HOTEL") ? "hotel" : "app_user") + " WHERE id=?",
            target);
    return cmd(
        actor, "MASTER_" + kind + "_DELETE", map("id", target, "version", row.get("version")));
  }

  @Test
  void deletingHotelHidesItAndInvalidatesOldQuotesWithoutRemovingData() {
    db.update("UPDATE hotel SET evaluation=80 WHERE id='h4'");
    var q = quote(rec, "h4", List.of(map("code", "DOUBLE", "quantity", 1)));
    long rows = db.count("SELECT COUNT(*) FROM daily_inventory WHERE hotel_id='h4'");
    var r = deletion(admin, "HOTEL", "h4");
    assertThat(str(r, "status")).isEqualTo("SUCCEEDED");
    assertThat(db.one("SELECT * FROM hotel WHERE id='h4'").get("deletedAt")).isNotNull();
    assertThat(db.count("SELECT COUNT(*) FROM daily_inventory WHERE hotel_id='h4'"))
        .isEqualTo(rows);
    assertThat(
            db.count("SELECT COUNT(*) FROM daily_inventory WHERE hotel_id='h4' AND stop_sale=TRUE"))
        .isEqualTo(rows);
    var catalog = master.catalog(admin);
    assertThat(objects(catalog, "hotels")).noneMatch(h -> str(h, "id").equals("h4"));
    assertThat(objects(catalog, "roomTypes")).noneMatch(h -> str(h, "hotelId").equals("h4"));
    assertThat(objects(catalog, "reportHotels")).anyMatch(h -> str(h, "id").equals("h4"));
    assertThat(
            str(
                cmd(rec, "BOOK", map("quoteId", q.get("quoteId"), "rulesConfirmed", true)),
                "businessCode"))
        .isEqualTo("HOTEL_DELETED");
    assertThat(occupied("h4")).isZero();
    assertThat(
            db.count(
                "SELECT COUNT(*) FROM audit_log WHERE object_id='h4' AND"
                    + " action_type='MASTER_HOTEL_DELETE' AND outcome='SUCCESS'"))
        .isEqualTo(1);
  }

  @Test
  void hotelsWithOpenBusinessOrActiveStaffCannotBeDeleted() {
    assertThat(str(deletion(admin, "HOTEL", "h1"), "businessCode"))
        .isEqualTo("UNFINISHED_BUSINESS");
    assertThat(str(deletion(admin, "HOTEL", "h2"), "businessCode"))
        .isEqualTo("HOTEL_ACCOUNT_BOUND");
    assertThat(deletion(admin, "USER", hotel2.id()).get("status")).isEqualTo("SUCCEEDED");
    assertThat(deletion(admin, "HOTEL", "h2").get("status")).isEqualTo("SUCCEEDED");
  }

  @Test
  void historicalOrdersFeesAndReportsRemainAfterDeletingHotel() {
    String order = book(quote(rec, "h3", List.of(map("code", "DOUBLE", "quantity", 1))));
    cmd(rec, "CANCEL", map("orderId", order));
    assertThat(deletion(admin, "HOTEL", "h3").get("status")).isEqualTo("SUCCEEDED");
    assertThat(str(object(booking.detail(admin, order), "order"), "hotelName")).isEqualTo("城市会议酒店");
    assertThat(db.count("SELECT COUNT(*) FROM fee_record WHERE order_id=?", order)).isEqualTo(1);
    var report =
        reports.report(
            admin, "created", LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 10), "h3");
    assertThat(objects(report, "orders")).anyMatch(o -> str(o, "id").equals(order));
  }

  @Test
  void deletedAccountCannotLogInOrUseExistingSessionAndGuestFactsRemain() throws Exception {
    long links = db.count("SELECT COUNT(*) FROM order_guest WHERE guest_id='g1'");
    assertThat(deletion(admin, "USER", "u-guest").get("status")).isEqualTo("SUCCEEDED");
    assertThat(db.count("SELECT COUNT(*) FROM order_guest WHERE guest_id='g1'")).isEqualTo(links);
    assertThat(db.count("SELECT COUNT(*) FROM guest WHERE id='g1'")).isEqualTo(1);
    mvc.perform(get("/api/me/stays").with(user("guest").roles("GUEST")))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/api/session").with(user("guest").roles("GUEST")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.user").doesNotExist())
        .andExpect(jsonPath("$.csrfToken").isString());
    mvc.perform(
            post("/api/session")
                .with(csrf())
                .param("username", "guest")
                .param("password", "Demo@123456"))
        .andExpect(status().isUnauthorized());
    assertThat(objects(master.catalog(admin), "users"))
        .noneMatch(u -> str(u, "id").equals("u-guest"));
  }

  @Test
  void selfDeletionAndResponsibleReceptionDeletionAreRejected() {
    assertThat(str(deletion(admin, "USER", admin.id()), "businessCode")).isEqualTo("SELF_DELETE");
    assertThat(str(deletion(admin, "USER", rec.id()), "businessCode"))
        .isEqualTo("UNFINISHED_BUSINESS");
    assertThat(
            db.count(
                "SELECT COUNT(*) FROM app_user WHERE role='ADMIN' AND enabled=TRUE AND deleted_at"
                    + " IS NULL"))
        .isEqualTo(1);
  }

  @Test
  void staffDeletionDoesNotStrandAnActiveHotel() {
    assertThat(str(deletion(admin, "USER", hotel.id()), "businessCode"))
        .isEqualTo("UNFINISHED_BUSINESS");
    master.saveUser(
        admin,
        map(
            "username",
            "hotel_backup",
            "displayName",
            "接班前台",
            "role",
            "HOTEL",
            "hotelId",
            "h1",
            "password",
            "Demo@123456",
            "enabled",
            true));
    assertThat(deletion(admin, "USER", hotel.id()).get("status")).isEqualTo("SUCCEEDED");
    assertThat(booking.list(access.byUsername("hotel_backup"))).isNotEmpty();
  }

  @Test
  void deletionIsIdempotentAndCannotBeUndoneByEditingOrReusingUsername() {
    String key = id();
    var body = map("id", "u-guest2", "version", 0);
    var r = commands.submit(admin, "MASTER_USER_DELETE", body, key);
    var deletedAt = db.one("SELECT deleted_at FROM app_user WHERE id='u-guest2'").get("deletedAt");
    assertThat(commands.submit(admin, "MASTER_USER_DELETE", body, key)).isEqualTo(r);
    assertThat(db.one("SELECT deleted_at FROM app_user WHERE id='u-guest2'").get("deletedAt"))
        .isEqualTo(deletedAt);
    assertThat(
            db.count(
                "SELECT COUNT(*) FROM audit_log WHERE object_id='u-guest2' AND"
                    + " action_type='MASTER_USER_DELETE' AND outcome='SUCCESS'"))
        .isEqualTo(1);
    assertThatThrownBy(
            () ->
                master.saveUser(
                    admin,
                    map(
                        "id",
                        "u-guest2",
                        "username",
                        "guest2",
                        "displayName",
                        "冯琳",
                        "role",
                        "GUEST",
                        "guestId",
                        "g2",
                        "enabled",
                        true)))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("账号已删除");
    assertThatThrownBy(
            () ->
                master.saveUser(
                    admin,
                    map(
                        "username",
                        "guest2",
                        "displayName",
                        "新来宾",
                        "role",
                        "GUEST",
                        "guestId",
                        "g2",
                        "password",
                        "Demo@123456",
                        "enabled",
                        true)))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("账号名已使用");
  }

  @Test
  void accountWithPendingRequestCannotBeDeleted() {
    db.insert(
        "operation_request",
        map(
            "id",
            id(),
            "actor_id",
            "u-guest2",
            "action_type",
            "NOTIFICATION_READ",
            "payload_hash",
            "test",
            "payload_json",
            "{}",
            "status",
            "PENDING",
            "created_at",
            LocalDateTime.of(2026, 11, 10, 13, 0),
            "updated_at",
            LocalDateTime.of(2026, 11, 10, 13, 0)));
    assertThat(str(deletion(admin, "USER", "u-guest2"), "businessCode"))
        .isEqualTo("UNFINISHED_BUSINESS");
  }

  @Test
  void nonAdministratorsAndMissingCsrfCannotDeleteObjects() throws Exception {
    for (String name : List.of("reception", "hotel", "guest")) {
      mvc.perform(
              delete("/api/admin/hotels/h4")
                  .with(user(name))
                  .with(csrf())
                  .header("Idempotency-Key", id())
                  .contentType("application/json")
                  .content("{\"version\":0}"))
          .andExpect(status().isForbidden());
    }
    mvc.perform(
            delete("/api/admin/users/u-guest2")
                .with(user("admin").roles("ADMIN"))
                .header("Idempotency-Key", id())
                .contentType("application/json")
                .content("{\"version\":0}"))
        .andExpect(status().isForbidden());
    assertThat(db.one("SELECT deleted_at FROM hotel WHERE id='h4'").get("deletedAt")).isNull();
  }

  @Test
  void stalePageCannotDeleteAnUpdatedObject() {
    var r = cmd(admin, "MASTER_HOTEL_DELETE", map("id", "h4", "version", 99));
    assertThat(str(r, "businessCode")).isEqualTo("VERSION_CONFLICT");
    assertThat(db.one("SELECT deleted_at FROM hotel WHERE id='h4'").get("deletedAt")).isNull();
  }

  @Test
  void concurrentAdministratorsCannotRemoveEveryActiveAdministrator() throws Exception {
    var created =
        master.saveUser(
            admin,
            map(
                "username",
                "other_admin",
                "displayName",
                "另一管理员",
                "role",
                "ADMIN",
                "password",
                "Demo@123456",
                "enabled",
                true));
    Actor other = access.byId(str(created, "id"));
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch go = new CountDownLatch(1);
    try {
      var first =
          pool.submit(
              () -> {
                go.await();
                return cmd(admin, "MASTER_USER_DELETE", map("id", other.id(), "version", 0));
              });
      var second =
          pool.submit(
              () -> {
                go.await();
                return cmd(other, "MASTER_USER_DELETE", map("id", admin.id(), "version", 0));
              });
      go.countDown();
      var results = List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
      long succeeded = results.stream().filter(r -> str(r, "status").equals("SUCCEEDED")).count();
      assertThat(succeeded).isLessThanOrEqualTo(1);
      assertThat(
              db.count(
                  "SELECT COUNT(*) FROM app_user WHERE role='ADMIN' AND enabled=TRUE AND deleted_at"
                      + " IS NULL"))
          .isEqualTo(2 - succeeded);
      if (succeeded == 0) {
        assertThat(deletion(admin, "USER", other.id()).get("status")).isEqualTo("SUCCEEDED");
      }
      var removed =
          Actor.from(
              db.one("SELECT * FROM app_user WHERE role='ADMIN' AND deleted_at IS NOT NULL"));
      var remaining =
          db.one(
              "SELECT * FROM app_user WHERE role='ADMIN' AND enabled=TRUE AND deleted_at IS NULL");
      assertThat(
              str(
                  cmd(
                      removed,
                      "MASTER_USER_DELETE",
                      map("id", remaining.get("id"), "version", remaining.get("version"))),
                  "businessCode"))
          .isEqualTo("UNAUTHENTICATED");
      assertThat(
              db.count(
                  "SELECT COUNT(*) FROM app_user WHERE role='ADMIN' AND enabled=TRUE AND deleted_at"
                      + " IS NULL"))
          .isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void invalidBudgetAndUnqualifiedHotelsAreNotCandidates() {
    var body = plan(List.of("g1"), List.of(map("code", "DOUBLE", "quantity", 1)));
    body.put("budget", 0);
    assertThatThrownBy(() -> search.saveDemand(rec, body)).isInstanceOf(BusinessException.class);
    var q = quote();
    var demand = db.one("SELECT * FROM quote_snapshot WHERE id=?", q.get("quoteId"));
    var options = search.search(rec, map("demandId", demand.get("demandId")));
    assertThat(options)
        .allSatisfy(o -> assertThat(str(object(o, "snapshot"), "hotelId")).isNotIn("h4", "h5"));
  }
}
