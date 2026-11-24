package cn.edu.campus.hospitality.search;

import static cn.edu.campus.hospitality.shared.Data.date;
import static cn.edu.campus.hospitality.shared.Data.decimal;
import static cn.edu.campus.hospitality.shared.Data.integer;
import static cn.edu.campus.hospitality.shared.Data.map;
import static cn.edu.campus.hospitality.shared.Data.objects;
import static cn.edu.campus.hospitality.shared.Data.require;
import static cn.edu.campus.hospitality.shared.Data.required;
import static cn.edu.campus.hospitality.shared.Data.str;
import static cn.edu.campus.hospitality.shared.Data.strings;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Holds the validated lodging dates, guest selections, room requirements, and search limits. */
public record Plan(
    String city,
    String siteId,
    LocalDate start,
    LocalDate end,
    BigDecimal budget,
    BigDecimal maxDistance,
    BigDecimal minPrice,
    BigDecimal maxPrice,
    List<Map<String, Object>> items,
    List<String> guestIds,
    String special) {
  /** Parses a request or snapshot into a plan and checks its business constraints. */
  public static Plan from(Map<String, Object> m) {
    Plan p =
        new Plan(
            required(m, "city"),
            required(m, "siteId"),
            date(m, "startDate"),
            date(m, "endDate"),
            decimal(m, "budget"),
            decimal(m, "maxDistance"),
            decimal(m, "minPrice"),
            decimal(m, "maxPrice"),
            objects(m, "items"),
            strings(m, "guestIds"),
            str(m, "special"));
    require(
        p.end.isAfter(p.start) && ChronoUnit.DAYS.between(p.start, p.end) <= 180,
        "VALIDATION",
        "离店须晚于入住，单次住宿最多 180 晚");
    require(p.budget.signum() > 0 && p.maxDistance.signum() > 0, "VALIDATION", "预算与最大距离必须大于 0");
    require(
        p.minPrice.signum() >= 0 && p.maxPrice.compareTo(p.minPrice) >= 0,
        "VALIDATION",
        "每日价格区间无效");
    require(!p.items.isEmpty() && !p.guestIds.isEmpty(), "VALIDATION", "请填写房型和来宾");
    require(p.guestIds.size() == new HashSet<>(p.guestIds).size(), "VALIDATION", "来宾不能重复");
    Set<String> codes = new HashSet<>();
    for (var item : p.items) {
      require(
          codes.add(required(item, "code"))
              && integer(item, "quantity") > 0
              && integer(item, "quantity") <= 100,
          "VALIDATION",
          "房型不可重复，房数应为 1～100");
    }
    require(p.special.length() <= 1000, "VALIDATION", "特殊需求过长");
    return p;
  }

  /** Returns the plan fields used in requests, persisted records, and quote snapshots. */
  public Map<String, Object> view() {
    return map(
        "city",
        city,
        "siteId",
        siteId,
        "startDate",
        start.toString(),
        "endDate",
        end.toString(),
        "budget",
        budget,
        "maxDistance",
        maxDistance,
        "minPrice",
        minPrice,
        "maxPrice",
        maxPrice,
        "items",
        items,
        "guestIds",
        guestIds,
        "special",
        special);
  }

  /** Returns the occupied nights from the arrival date up to the departure date. */
  public List<LocalDate> dates() {
    return start.datesUntil(end).toList();
  }
}
