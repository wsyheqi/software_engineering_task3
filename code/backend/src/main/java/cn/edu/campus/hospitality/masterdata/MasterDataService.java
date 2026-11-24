package cn.edu.campus.hospitality.masterdata;

import static cn.edu.campus.hospitality.shared.Data.bool;
import static cn.edu.campus.hospitality.shared.Data.decimal;
import static cn.edu.campus.hospitality.shared.Data.id;
import static cn.edu.campus.hospitality.shared.Data.integer;
import static cn.edu.campus.hospitality.shared.Data.map;
import static cn.edu.campus.hospitality.shared.Data.number;
import static cn.edu.campus.hospitality.shared.Data.require;
import static cn.edu.campus.hospitality.shared.Data.required;
import static cn.edu.campus.hospitality.shared.Data.str;

import cn.edu.campus.hospitality.audit.AuditService;
import cn.edu.campus.hospitality.identity.Access;
import cn.edu.campus.hospitality.identity.AccountLocks;
import cn.edu.campus.hospitality.identity.Actor;
import cn.edu.campus.hospitality.inventory.InventoryService;
import cn.edu.campus.hospitality.shared.BusinessClock;
import cn.edu.campus.hospitality.shared.BusinessException;
import cn.edu.campus.hospitality.shared.CommandHandler;
import cn.edu.campus.hospitality.shared.Db;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Maintains shared hotel, location, account, and policy records. */
@Service
public class MasterDataService implements CommandHandler {
  private final Db db;
  private final Access access;
  private final InventoryService inventory;
  private final AuditService audit;
  private final BusinessClock clock;
  private final PasswordEncoder passwords;
  private final AccountLocks accountLocks;

  /**
   * Creates the catalog service with hotel inventory, auditing, and account lifecycle dependencies.
   */
  public MasterDataService(
      Db db,
      Access access,
      InventoryService inventory,
      AuditService audit,
      BusinessClock clock,
      PasswordEncoder passwords,
      AccountLocks accountLocks) {
    this.db = db;
    this.access = access;
    this.inventory = inventory;
    this.audit = audit;
    this.clock = clock;
    this.passwords = passwords;
    this.accountLocks = accountLocks;
  }

  /** Returns the reference data and guest information visible within the actor's scope. */
  public Map<String, Object> catalog(Actor a) {
    access.roles(a, "ADMIN", "RECEPTION", "HOTEL");
    var hotels = db.rows("SELECT * FROM hotel WHERE deleted_at IS NULL ORDER BY id");
    List<Map<String, Object>> guests =
        a.is("RECEPTION")
            ? db.rows("SELECT * FROM guest WHERE owner_id=? ORDER BY name", a.id())
            : a.is("HOTEL")
                ? db.rows(
                    "SELECT DISTINCT g.* FROM guest g JOIN order_guest x ON g.id=x.guest_id JOIN"
                        + " booking_order o ON o.id=x.order_id WHERE o.hotel_id=? ORDER BY g.name",
                    a.hotelId())
                : db.rows("SELECT * FROM guest ORDER BY name");
    if (a.is("ADMIN")) {
      for (var g : guests) {
        g.put("phone", Access.maskPhone(str(g, "phone")));
      }
    }
    return map(
        "hotels",
        hotels,
        "reportHotels",
        db.rows("SELECT * FROM hotel ORDER BY id"),
        "sites",
        db.rows("SELECT * FROM reception_site ORDER BY id"),
        "distances",
        db.rows(
            "SELECT d.* FROM hotel_site_distance d JOIN hotel h ON h.id=d.hotel_id WHERE"
                + " h.deleted_at IS NULL ORDER BY d.hotel_id,d.site_id"),
        "roomTypes",
        db.rows(
            "SELECT r.* FROM room_type r JOIN hotel h ON h.id=r.hotel_id WHERE h.deleted_at IS NULL"
                + " ORDER BY r.hotel_id,r.code"),
        "guests",
        guests,
        "rules",
        a.is("HOTEL")
            ? db.rows(
                "SELECT r.* FROM fee_rule r JOIN hotel h ON h.id=r.hotel_id WHERE r.hotel_id=? AND"
                    + " h.deleted_at IS NULL",
                a.hotelId())
            : db.rows(
                "SELECT r.* FROM fee_rule r JOIN hotel h ON h.id=r.hotel_id WHERE h.deleted_at IS"
                    + " NULL ORDER BY r.hotel_id"),
        "users",
        a.is("ADMIN")
            ? db.rows(
                "SELECT id,username,display_name,role,hotel_id,guest_id,email,enabled,version FROM"
                    + " app_user WHERE deleted_at IS NULL ORDER BY role,username")
            : List.of(),
        "today",
        clock.today(),
        "now",
        clock.now());
  }

  @Override
  public Set<String> actions() {
    return Set.of(
        "MASTER_HOTEL",
        "MASTER_SITE",
        "MASTER_DISTANCE",
        "MASTER_RULE",
        "MASTER_HOTEL_DELETE",
        "MASTER_USER_DELETE",
        "GUEST_SAVE",
        "ROOM_TYPE",
        "BLOCK_RETRY",
        "BLOCK_RELEASE");
  }

  @Override
  public Map<String, Object> handle(Actor a, String action, Map<String, Object> b) {
    if (action.equals("GUEST_SAVE")) {
      return guest(a, b);
    }
    if (action.equals("ROOM_TYPE")) {
      return room(a, b);
    }
    if (action.startsWith("BLOCK_")) {
      return block(a, b, action.equals("BLOCK_RELEASE"));
    }
    access.roles(a, "ADMIN");
    return switch (action) {
      case "MASTER_HOTEL" -> hotel(b);
      case "MASTER_SITE" -> site(b);
      case "MASTER_DISTANCE" -> distance(b);
      case "MASTER_RULE" -> rule(b);
      case "MASTER_HOTEL_DELETE" -> deleteHotel(a, b);
      case "MASTER_USER_DELETE" -> deleteUser(a, b);
      default -> throw new IllegalArgumentException(action);
    };
  }

  private String targetId(Map<String, Object> body) {
    String target = str(body, "id");
    return target.isBlank() ? id() : target;
  }

  private Map<String, Object> hotel(Map<String, Object> b) {
    String id = targetId(b);
    boolean exists = db.find("SELECT id FROM hotel WHERE id=?", id).isPresent();
    if (exists) {
      inventory.lockHotels(List.of(id), true);
    }
    BigDecimal evaluation =
        b.get("evaluation") == null || str(b, "evaluation").isBlank()
            ? null
            : decimal(b, "evaluation");
    require(
        evaluation == null
            || (evaluation.signum() >= 0 && evaluation.compareTo(new BigDecimal("100")) <= 0),
        "VALIDATION",
        "学校评价为 0～100 或留空");
    if (exists) {
      db.update(
          "UPDATE hotel SET"
              + " name=?,city=?,address=?,description=?,audited=?,evaluation=?,version=version+1"
              + " WHERE id=?",
          required(b, "name"),
          required(b, "city"),
          required(b, "address"),
          str(b, "description"),
          bool(b, "audited"),
          evaluation,
          id);
    } else {
      db.insert(
          "hotel",
          map(
              "id",
              id,
              "name",
              required(b, "name"),
              "city",
              required(b, "city"),
              "address",
              required(b, "address"),
              "description",
              str(b, "description"),
              "audited",
              bool(b, "audited"),
              "evaluation",
              evaluation));
      defaultRule(id);
    }
    return map("id", id, "hotelId", id, "message", "酒店资料与学校审核依据已保存");
  }

  /** Initializes a hotel's default cancellation and stay fee terms. */
  public void defaultRule(String hotel) {
    db.insert(
        "fee_rule",
        map(
            "hotel_id",
            hotel,
            "version",
            1,
            "cancellation_text",
            "入住前一日18:00前免费取消；18:00整起按各间房锁定首晚价格之和记录整单取消费用，线下结算。",
            "early_checkout_text",
            "提前退房只计实际已住夜晚；额外费用须依据下单时展示的约定，由酒店提出、接待核对。未约定不得加收。",
            "no_show_text",
            "未入住费用按校方与酒店已展示的约定另行记录和核对，不按未展示的新规则收费。"));
  }

  private Map<String, Object> site(Map<String, Object> b) {
    String id = targetId(b);
    var old = db.find("SELECT * FROM reception_site WHERE id=? FOR UPDATE", id);
    if (old.isPresent()) {
      require(
          number(b, "version") == number(old.get(), "version"), "VERSION_CONFLICT", "地点已变化，请刷新");
      db.update(
          "UPDATE reception_site SET name=?,address=?,version=version+1 WHERE id=?",
          required(b, "name"),
          required(b, "address"),
          id);
    } else {
      db.insert(
          "reception_site",
          map("id", id, "name", required(b, "name"), "address", required(b, "address")));
    }
    return map("id", id, "message", "接待地点已保存，请核实对应酒店距离");
  }

  private Map<String, Object> distance(Map<String, Object> b) {
    String hotel = required(b, "hotelId");
    String site = required(b, "siteId");
    inventory.lockHotels(List.of(hotel), true);
    db.one("SELECT * FROM reception_site WHERE id=? FOR UPDATE", site);
    BigDecimal d = decimal(b, "distanceKm");
    require(d.signum() >= 0, "VALIDATION", "距离不能为负数");
    if (db.count(
            "SELECT COUNT(*) FROM hotel_site_distance WHERE hotel_id=? AND site_id=?", hotel, site)
        > 0) {
      db.update(
          "UPDATE hotel_site_distance SET distance_km=?,version=version+1 WHERE hotel_id=? AND"
              + " site_id=?",
          d,
          hotel,
          site);
    } else {
      db.insert("hotel_site_distance", map("hotel_id", hotel, "site_id", site, "distance_km", d));
    }
    return map("hotelId", hotel, "message", "校方核实距离已保存");
  }

  private Map<String, Object> rule(Map<String, Object> b) {
    String hotel = required(b, "hotelId");
    inventory.lockHotels(List.of(hotel), true);
    db.update(
        "UPDATE fee_rule SET early_checkout_text=?,no_show_text=?,version=version+1 WHERE"
            + " hotel_id=?",
        required(b, "earlyCheckoutText"),
        required(b, "noShowText"),
        hotel);
    return map("hotelId", hotel, "message", "新约定规则已保存；已确认订单继续使用原规则快照");
  }

  private Map<String, Object> guest(Actor a, Map<String, Object> b) {
    access.roles(a, "RECEPTION");
    String id = targetId(b);
    String phone = required(b, "phone");
    require(phone.matches("[0-9+() -]{6,32}"), "VALIDATION", "联系方式格式无效");
    String name = required(b, "name");
    require(name.length() <= 80, "VALIDATION", "姓名过长");
    var old = db.find("SELECT * FROM guest WHERE id=? FOR UPDATE", id);
    if (old.isPresent()) {
      access.check(a, a.id().equals(str(old.get(), "ownerId")), id, null, null, "只能维护负责的来宾");
      db.update("UPDATE guest SET name=?,phone=? WHERE id=?", name, phone, id);
    } else {
      db.insert(
          "guest",
          map(
              "id",
              id,
              "owner_id",
              a.id(),
              "name",
              name,
              "phone",
              phone,
              "identity_ref",
              required(b, "identityRef")));
    }
    return map("guestId", id, "message", "来宾资料已保存");
  }

  private Map<String, Object> room(Actor a, Map<String, Object> b) {
    String hotel = required(b, "hotelId");
    access.hotel(a, hotel);
    inventory.lockHotels(List.of(hotel), true);
    String code = required(b, "code").toUpperCase(Locale.ROOT);
    int capacity = integer(b, "capacity");
    require(
        code.matches("[A-Z0-9_]{1,32}") && capacity > 0 && capacity <= 20,
        "VALIDATION",
        "房型代码或容量无效");
    if (!str(b, "id").isBlank()) {
      var old = db.one("SELECT * FROM room_type WHERE id=?", b.get("id"));
      access.hotel(a, str(old, "hotelId"));
      require(code.equals(str(old, "code")), "VALIDATION", "已有房型代码不能更换");
      db.update(
          "UPDATE room_type SET name=?,capacity=? WHERE id=?",
          required(b, "name"),
          capacity,
          old.get("id"));
      db.update("UPDATE hotel SET version=version+1 WHERE id=?", hotel);
      return map(
          "hotelId", hotel, "roomTypeId", old.get("id"), "message", "房型名称与容量已更新，已确认订单的原明细保持不变");
    }
    require(
        db.count("SELECT COUNT(*) FROM room_type WHERE hotel_id=? AND code=?", hotel, code) == 0,
        "ROOM_TYPE_EXISTS",
        "本酒店已存在该房型代码");
    String room = id();
    db.insert(
        "room_type",
        map(
            "id",
            room,
            "hotel_id",
            hotel,
            "code",
            code,
            "name",
            required(b, "name"),
            "capacity",
            capacity));
    int quota = integer(b, "quota");
    BigDecimal price = decimal(b, "price");
    require(quota >= 0 && price.signum() >= 0, "VALIDATION", "价格和学校名额不能为负数");
    for (LocalDate day = clock.today();
        day.isBefore(clock.today().plusDays(181));
        day = day.plusDays(1)) {
      db.insert(
          "daily_inventory",
          map(
              "id",
              id(),
              "hotel_id",
              hotel,
              "room_type_id",
              room,
              "stay_date",
              day,
              "price",
              price,
              "quota",
              quota));
    }
    return map("roomTypeId", room, "hotelId", hotel, "message", "房型已新增，并建立未来 180 天的初始每日房源");
  }

  private Map<String, Object> block(Actor a, Map<String, Object> b, boolean release) {
    var before = db.one("SELECT * FROM inventory_block WHERE id=?", required(b, "id"));
    String hotel = str(before, "hotelId");
    access.hotel(a, hotel);
    inventory.lockHotels(List.of(hotel), false);
    var block = db.one("SELECT * FROM inventory_block WHERE id=? FOR UPDATE", before.get("id"));
    require(
        (release ? "APPLIED" : "PENDING").equals(str(block, "status")),
        "STATE_CONFLICT",
        "停用记录状态不允许此操作");
    var rows =
        db.rows(
            "SELECT * FROM daily_inventory WHERE room_type_id=? AND stay_date>=? AND stay_date<?"
                + " ORDER BY stay_date FOR UPDATE",
            block.get("roomTypeId"),
            block.get("startDate"),
            block.get("endDate"));
    int quantity = integer(block, "quantity");
    for (var row : rows) {
      require(
          release
              ? integer(row, "blocked") >= quantity
              : integer(row, "quota") - integer(row, "occupied") - integer(row, "blocked")
                  >= quantity,
          "QUOTA_CONFLICT",
          "仍存在名额冲突，请先协调受影响订单");
    }
    for (var row : rows) {
      db.update(
          "UPDATE daily_inventory SET blocked=blocked+?,version=version+1 WHERE id=?",
          release ? -quantity : quantity,
          row.get("id"));
    }
    db.update(
        "UPDATE inventory_block SET status=? WHERE id=?",
        release ? "RELEASED" : "APPLIED",
        block.get("id"));
    db.update(
        "UPDATE work_item SET status='RESOLVED' WHERE source_key=?", "block:" + block.get("id"));
    return map("hotelId", hotel, "message", release ? "停用扣减已解除，停售状态保持独立" : "冲突已解除，停用区间扣减已生效");
  }

  /** Creates or updates an account after validating the role and scope bindings. */
  @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
  public Map<String, Object> saveUser(Actor a, Map<String, Object> b) {
    a = accountLocks.active(a);
    access.roles(a, "ADMIN");
    String id = targetId(b);
    String role = required(b, "role");
    String username = required(b, "username");
    require(Set.of("ADMIN", "RECEPTION", "HOTEL", "GUEST").contains(role), "VALIDATION", "角色无效");
    require(username.matches("[A-Za-z0-9_.-]{3,60}"), "VALIDATION", "账号应为 3～60 位字母数字及 ._- ");
    var old = db.find("SELECT * FROM app_user WHERE id=? FOR UPDATE", id);
    boolean enabled = b.get("enabled") == null || bool(b, "enabled");
    require(
        old.isEmpty() || old.get().get("deletedAt") == null, "ACCOUNT_DELETED", "账号已删除，不能通过编辑重新启用");
    if (old.isEmpty()) {
      require(
          db.count("SELECT COUNT(*) FROM app_user WHERE username=?", username) == 0,
          "ACCOUNT_EXISTS",
          "账号名已使用（含已删除账号），请使用不同账号名");
    }
    require(
        !a.id().equals(id) || (enabled && role.equals("ADMIN")),
        "ACCOUNT_CONFLICT",
        "不能停用自身管理员账号或移除自身角色");
    if (old.isPresent()) {
      require(str(old.get(), "role").equals(role), "ACCOUNT_CONFLICT", "已使用账号的角色不可直接更换，请新建适当角色账号");
    }
    String hotel = null;
    String guest = null;
    if (role.equals("HOTEL")) {
      hotel = required(b, "hotelId");
      db.one("SELECT id FROM hotel WHERE id=? AND deleted_at IS NULL", hotel);
    }
    if (role.equals("GUEST")) {
      guest = required(b, "guestId");
      db.one("SELECT id FROM guest WHERE id=?", guest);
      require(
          db.count(
                  "SELECT COUNT(*) FROM app_user WHERE guest_id=? AND id<>? AND deleted_at IS NULL",
                  guest,
                  id)
              == 0,
          "ACCOUNT_CONFLICT",
          "来宾已绑定账号");
    }
    String password = str(b, "password");
    require(old.isPresent() || password.length() >= 10, "VALIDATION", "新账号密码至少 10 位");
    require(password.isBlank() || password.length() >= 10, "VALIDATION", "密码至少 10 位");
    String hash =
        password.isBlank() ? str(old.orElse(map()), "passwordHash") : passwords.encode(password);
    if (old.isPresent()) {
      db.update(
          "UPDATE app_user SET"
              + " display_name=?,hotel_id=?,guest_id=?,email=?,enabled=?,password_hash=?,"
              + "version=version+1"
              + " WHERE id=?",
          required(b, "displayName"),
          hotel,
          guest,
          str(b, "email"),
          enabled,
          hash,
          id);
    } else {
      db.insert(
          "app_user",
          map(
              "id",
              id,
              "username",
              username,
              "password_hash",
              hash,
              "display_name",
              required(b, "displayName"),
              "role",
              role,
              "hotel_id",
              hotel,
              "guest_id",
              guest,
              "email",
              str(b, "email"),
              "enabled",
              enabled));
    }
    audit.record(a, "ACCOUNT_SAVE", id, hotel, null, null, "SUCCESS", "管理员维护账号与绑定关系");
    return map("id", id, "message", "账号已保存");
  }

  private Map<String, Object> deleteHotel(Actor actor, Map<String, Object> body) {
    String target = required(body, "id");
    var hotel = db.one("SELECT * FROM hotel WHERE id=? FOR UPDATE", target);
    if (hotel.get("deletedAt") != null) {
      return map("objectId", target, "hotelId", target, "message", "酒店已经删除，历史记录继续保留");
    }
    require(
        number(body, "version") == number(hotel, "version"),
        "VERSION_CONFLICT",
        "酒店资料已变化，请刷新后重新确认删除");
    require(
        db.count(
                "SELECT COUNT(*) FROM booking_order WHERE hotel_id=? AND status IN"
                    + " ('PENDING','CONFIRMED','CHECKED_IN','RELOCATING')",
                target)
            == 0,
        "UNFINISHED_BUSINESS",
        "酒店仍有未完成订单，请先完成接待或停业安置后再删除");
    require(
        db.count(
                "SELECT COUNT(*) FROM relocation_case c JOIN booking_order o ON o.id=c.order_id"
                    + " WHERE o.hotel_id=? AND c.status='OPEN'",
                target)
            == 0,
        "UNFINISHED_BUSINESS",
        "酒店仍有待安置事项，请先处理");
    require(
        db.count(
                "SELECT COUNT(*) FROM fee_record f JOIN booking_order o ON o.id=f.order_id WHERE"
                    + " o.hotel_id=? AND f.status IN ('PENDING','DISPUTED')",
                target)
            == 0,
        "UNFINISHED_BUSINESS",
        "酒店仍有待核对或争议费用，请先处理");
    require(
        db.count(
                "SELECT COUNT(*) FROM app_user WHERE hotel_id=? AND enabled=TRUE AND deleted_at IS"
                    + " NULL",
                target)
            == 0,
        "HOTEL_ACCOUNT_BOUND",
        "酒店仍绑定有效工作人员账号，请先停用、删除或调整账号绑定后再删除酒店");
    db.update(
        "UPDATE daily_inventory SET stop_sale=TRUE,version=version+1 WHERE hotel_id=?", target);
    db.update(
        "UPDATE hotel SET deleted_at=?,deleted_by=?,version=version+1 WHERE id=?",
        clock.now(),
        actor.id(),
        target);
    return map(
        "businessCode",
        "HOTEL_REMOVED",
        "objectId",
        target,
        "hotelId",
        target,
        "message",
        "酒店已删除，已移出可选列表，历史订单与日志保留");
  }

  private Map<String, Object> deleteUser(Actor actor, Map<String, Object> body) {
    String target = required(body, "id");
    require(!actor.id().equals(target), "SELF_DELETE", "不能删除当前登录账号");
    var user = db.one("SELECT * FROM app_user WHERE id=? FOR UPDATE", target);
    if (user.get("deletedAt") != null) {
      return map("objectId", target, "hotelId", user.get("hotelId"), "message", "账号已经删除，历史记录继续保留");
    }
    require(
        number(body, "version") == number(user, "version"),
        "VERSION_CONFLICT",
        "账号资料已变化，请刷新后重新确认删除");
    if ("ADMIN".equals(str(user, "role")) && bool(user, "enabled")) {
      require(
          db.count(
                  "SELECT COUNT(*) FROM app_user WHERE role='ADMIN' AND enabled=TRUE AND deleted_at"
                      + " IS NULL")
              > 1,
          "LAST_ADMIN",
          "不能删除最后一个有效管理员");
    }
    require(
        db.count(
                "SELECT COUNT(*) FROM operation_request WHERE actor_id=? AND status='PENDING'",
                target)
            == 0,
        "UNFINISHED_BUSINESS",
        "账号仍有处理中请求，请待请求结束后再删除");
    if ("RECEPTION".equals(str(user, "role"))) {
      require(
          db.count(
                  "SELECT COUNT(*) FROM booking_order WHERE owner_id=? AND status IN"
                      + " ('PENDING','CONFIRMED','CHECKED_IN','RELOCATING')",
                  target)
              == 0,
          "UNFINISHED_BUSINESS",
          "接待人员仍有负责的未完成订单，请先处理或转交后再删除");
      require(
          db.count(
                  "SELECT COUNT(*) FROM fee_record f JOIN booking_order o ON o.id=f.order_id WHERE"
                      + " o.owner_id=? AND f.status IN ('PENDING','DISPUTED')",
                  target)
              == 0,
          "UNFINISHED_BUSINESS",
          "接待人员仍有待核对或争议费用，请先处理");
    }
    if ("HOTEL".equals(str(user, "role")) && bool(user, "enabled")) {
      String hotel = str(user, "hotelId");
      boolean last =
          db.count(
                  "SELECT COUNT(*) FROM app_user WHERE hotel_id=? AND role='HOTEL' AND enabled=TRUE"
                      + " AND deleted_at IS NULL",
                  hotel)
              <= 1;
      if (last) {
        require(
            db.count(
                    "SELECT COUNT(*) FROM booking_order WHERE hotel_id=? AND status IN"
                        + " ('PENDING','CONFIRMED','CHECKED_IN','RELOCATING')",
                    hotel)
                == 0,
            "UNFINISHED_BUSINESS",
            "该账号是酒店最后一个有效工作人员，仍有未完成订单时须先安排其他有效工作人员");
      }
    }
    db.update(
        "UPDATE app_user SET enabled=FALSE,deleted_at=?,deleted_by=?,version=version+1 WHERE id=?",
        clock.now(),
        actor.id(),
        target);
    return map(
        "businessCode",
        "ACCOUNT_REMOVED",
        "objectId",
        target,
        "hotelId",
        user.get("hotelId"),
        "message",
        "账号已删除并失去登录权限，历史责任关系与操作日志保留");
  }

  /** Discloses a contact number for a recorded exception and audits the reason. */
  @Transactional
  public Map<String, Object> contact(Actor a, Map<String, Object> b) {
    access.roles(a, "ADMIN");
    String reason = required(b, "reason");
    String exception = required(b, "exceptionId");
    String kind = required(b, "exceptionType");
    require(reason.length() <= 500, "VALIDATION", "原因过长");
    String order;
    if (kind.equals("RELOCATION")) {
      var c = db.one("SELECT * FROM relocation_case WHERE id=? AND status='OPEN'", exception);
      order = str(c, "orderId");
    } else if (kind.equals("FEE")) {
      var fee = db.one("SELECT * FROM fee_record WHERE id=? AND status='DISPUTED'", exception);
      order = str(fee, "orderId");
    } else {
      throw new BusinessException("EXCEPTION_REQUIRED", "只能在具体安置或费用争议中受控查看完整号码");
    }
    var target = db.one("SELECT * FROM booking_order WHERE id=?", order);
    audit.record(
        a,
        "CONTACT_ACCESS",
        exception,
        str(target, "hotelId"),
        order,
        null,
        "SUCCESS",
        "异常查看完整号码，原因：" + reason);
    return map(
        "contacts",
        db.rows(
            "SELECT g.id,g.name,g.phone FROM guest g JOIN order_guest x ON x.guest_id=g.id WHERE"
                + " x.order_id=?",
            order),
        "accessedAt",
        clock.now());
  }
}
