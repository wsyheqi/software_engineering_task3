package cn.edu.campus.hospitality.shared;

import static cn.edu.campus.hospitality.shared.Data.integer;
import static cn.edu.campus.hospitality.shared.Data.map;
import static cn.edu.campus.hospitality.shared.Data.require;
import static cn.edu.campus.hospitality.shared.Data.str;

import cn.edu.campus.hospitality.audit.AuditService;
import cn.edu.campus.hospitality.booking.BookingService;
import cn.edu.campus.hospitality.identity.Access;
import cn.edu.campus.hospitality.inventory.InventoryService;
import cn.edu.campus.hospitality.masterdata.MasterDataService;
import cn.edu.campus.hospitality.notification.NotificationService;
import cn.edu.campus.hospitality.reporting.ReportingService;
import cn.edu.campus.hospitality.search.SearchService;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Maps HTTP requests to scoped queries and persistent business commands. */
@RestController
@RequestMapping("/api")
public class ApiController {
  private final Access access;
  private final CommandService commands;
  private final MasterDataService master;
  private final SearchService search;
  private final BookingService bookings;
  private final InventoryService inventory;
  private final NotificationService notices;
  private final ReportingService reports;
  private final AuditService audit;
  private final Db db;
  private final Json json;

  /** Connects the REST endpoints to the services that validate and execute their operations. */
  public ApiController(
      Access access,
      CommandService commands,
      MasterDataService master,
      SearchService search,
      BookingService bookings,
      InventoryService inventory,
      NotificationService notices,
      ReportingService reports,
      AuditService audit,
      Db db,
      Json json) {
    this.access = access;
    this.commands = commands;
    this.master = master;
    this.search = search;
    this.bookings = bookings;
    this.inventory = inventory;
    this.notices = notices;
    this.reports = reports;
    this.audit = audit;
    this.db = db;
    this.json = json;
  }

  /** Returns the reference catalog available to the current actor. */
  @GetMapping("/catalog")
  public Object catalog() {
    return master.catalog(access.current());
  }

  /** Saves a new or versioned lodging demand. */
  @PostMapping("/demands")
  public Object demand(@RequestBody Map<String, Object> body) {
    return search.saveDemand(access.current(), body);
  }

  /** Updates the demand identified by the request path. */
  @PutMapping("/demands/{id}")
  public Object updateDemand(@PathVariable String id, @RequestBody Map<String, Object> body) {
    body.put("id", id);
    return search.saveDemand(access.current(), body);
  }

  /** Searches for eligible hotels and returns their quote snapshots. */
  @PostMapping("/hotel-options/search")
  public Object options(@RequestBody Map<String, Object> body) {
    return search.search(access.current(), body);
  }

  /** Returns a quote snapshot after checking ownership or order access. */
  @GetMapping("/quotes/{id}")
  public Object quote(@PathVariable String id) {
    var actor = access.current();
    var quote = search.quote(id);
    if (quote.get("orderId") != null) {
      access.readOrder(
          actor, db.one("SELECT * FROM booking_order WHERE id=?", quote.get("orderId")));
    } else {
      access.check(actor, actor.id().equals(str(quote, "actorId")), id, null, null, "方案不在本人范围内");
    }
    return Map.of("id", id, "snapshot", json.read(str(quote, "snapshotJson")));
  }

  /** Lists orders visible to the current actor. */
  @GetMapping("/bookings")
  public Object orders() {
    return bookings.list(access.current());
  }

  /** Returns the permitted detail of a specific order. */
  @GetMapping("/bookings/{id}")
  public Object order(@PathVariable String id) {
    return bookings.detail(access.current(), id);
  }

  /** Lists the stays belonging to the current guest. */
  @GetMapping("/me/stays")
  public Object own() {
    return bookings.guestStays(access.current());
  }

  /** Returns the processing result of the actor's previous operation request. */
  @GetMapping("/requests/{id}")
  public Object request(@PathVariable String id) {
    return commands.query(access.current(), id);
  }

  /** Submits a booking with a client-supplied idempotency key. */
  @PostMapping("/bookings")
  public Object book(
      @RequestHeader("Idempotency-Key") String key, @RequestBody Map<String, Object> b) {
    return command("BOOK", b, key);
  }

  /** Dispatches the requested order operation to its command handler. */
  @PostMapping("/bookings/{id}/{action}")
  public Object orderAction(
      @PathVariable String id,
      @PathVariable String action,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody Map<String, Object> b) {
    String type =
        switch (action) {
          case "changes" -> "CHANGE";
          case "cancellations" -> "CANCEL";
          case "check-ins" -> "CHECK_IN";
          case "actual-nights" -> "ACTUAL_NIGHT";
          case "check-outs" -> "CHECK_OUT";
          case "late-arrival-holds" -> "LATE_HOLD";
          case "no-shows" -> "NO_SHOW";
          case "extensions" -> "EXTENSION_PROPOSE";
          case "fees" -> "FEE_PROPOSE";
          case "special-changes" -> "SPECIAL_REQUEST";
          default -> throw new BusinessException("NOT_FOUND", "接口不存在", Map.of(), 404);
        };
    b.put("orderId", id);
    return command(type, b, key);
  }

  /** Records one side's approval of a special booking change. */
  @PostMapping("/change-approvals/{id}/confirmations")
  public Object approve(
      @PathVariable String id,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody Map<String, Object> b) {
    b.put("id", id);
    return command("SPECIAL_CONFIRM", b, key);
  }

  /** Submits a confirmation of a proposed stay extension. */
  @PostMapping("/extensions/{id}/confirmations")
  public Object extend(
      @PathVariable String id,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody Map<String, Object> b) {
    b.put("id", id);
    return command("EXTENSION_CONFIRM", b, key);
  }

  /** Submits a review or resolution of a fee record. */
  @PostMapping("/fees/{id}/{action}")
  public Object fee(
      @PathVariable String id,
      @PathVariable String action,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody Map<String, Object> b) {
    require(Set.of("reviews", "resolutions").contains(action), "VALIDATION", "操作无效");
    b.put("id", id);
    return command(action.equals("reviews") ? "FEE_REVIEW" : "FEE_RESOLVE", b, key);
  }

  /** Returns the permitted hotel's daily inventory for the requested interval. */
  @GetMapping("/hotels/{id}/daily-inventory")
  public Object inventory(
      @PathVariable String id, @RequestParam LocalDate from, @RequestParam LocalDate to) {
    return inventory.list(access.current(), id, from, to);
  }

  /** Submits versioned daily price and quota adjustments. */
  @PutMapping("/hotels/{id}/daily-inventory")
  public Object inventoryUpdate(
      @PathVariable String id,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody Map<String, Object> b) {
    b.put("hotelId", id);
    return command("INVENTORY_UPDATE", b, key);
  }

  /** Submits a request to block rooms over an interval. */
  @PostMapping("/hotels/{id}/blocks")
  public Object block(
      @PathVariable String id,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody Map<String, Object> b) {
    b.put("hotelId", id);
    return command("INVENTORY_BLOCK", b, key);
  }

  /** Lists the block records within the permitted hotel. */
  @GetMapping("/hotels/{id}/blocks")
  public Object blocks(@PathVariable String id) {
    access.hotel(access.current(), id);
    return db.rows("SELECT * FROM inventory_block WHERE hotel_id=? ORDER BY created_at DESC", id);
  }

  /** Retries or releases an existing room block. */
  @PostMapping("/blocks/{id}/{action}")
  public Object blockAction(
      @PathVariable String id,
      @PathVariable String action,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody Map<String, Object> b) {
    require(Set.of("apply", "release").contains(action), "VALIDATION", "操作无效");
    b.put("id", id);
    return command(action.equals("apply") ? "BLOCK_RETRY" : "BLOCK_RELEASE", b, key);
  }

  /** Submits a room type change for the selected hotel. */
  @PostMapping("/hotels/{id}/room-types")
  public Object room(
      @PathVariable String id,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody Map<String, Object> b) {
    b.put("hotelId", id);
    return command("ROOM_TYPE", b, key);
  }

  /** Submits a hotel closure for the requested dates. */
  @PostMapping("/hotels/{id}/closures")
  public Object closure(
      @PathVariable String id,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody Map<String, Object> b) {
    b.put("hotelId", id);
    return command("CLOSURE", b, key);
  }

  /** Confirms a replacement quote for a relocation case. */
  @PostMapping("/relocations/{id}/confirmations")
  public Object relocate(
      @PathVariable String id,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody Map<String, Object> b) {
    b.put("id", id);
    return command("RELOCATE", b, key);
  }

  /** Submits a guest record update for the current reception user. */
  @PostMapping("/guests")
  public Object guest(
      @RequestHeader("Idempotency-Key") String key, @RequestBody Map<String, Object> b) {
    return command("GUEST_SAVE", b, key);
  }

  /** Dispatches an administrator's catalog change or exceptional contact request. */
  @PostMapping("/admin/{kind}")
  public Object admin(
      @PathVariable String kind,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestBody Map<String, Object> b) {
    if (kind.equals("users")) {
      return master.saveUser(access.current(), b);
    }
    String type =
        switch (kind) {
          case "hotels" -> "MASTER_HOTEL";
          case "sites" -> "MASTER_SITE";
          case "distances" -> "MASTER_DISTANCE";
          case "rules" -> "MASTER_RULE";
          case "contact-accesses" -> "CONTACT_ACCESS";
          default -> throw new BusinessException("NOT_FOUND", "管理接口不存在", Map.of(), 404);
        };
    if (type.equals("CONTACT_ACCESS")) {
      return master.contact(access.current(), b);
    }
    return command(type, b, key);
  }

  /** Submits a guarded soft deletion of a hotel or account. */
  @DeleteMapping("/admin/{kind}/{id}")
  public Object deleteAdminObject(
      @PathVariable String kind,
      @PathVariable String id,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody Map<String, Object> body) {
    String action =
        switch (kind) {
          case "hotels" -> "MASTER_HOTEL_DELETE";
          case "users" -> "MASTER_USER_DELETE";
          default -> throw new BusinessException("NOT_FOUND", "删除接口不存在", Map.of(), 404);
        };
    body.put("id", id);
    return command(action, body, key);
  }

  /** Returns the current actor's in-app notifications. */
  @GetMapping("/notifications")
  public Object notifications() {
    return notices.notifications(access.current());
  }

  /** Returns the work queues visible to the current actor. */
  @GetMapping("/todos")
  public Object todos() {
    return notices.todos(access.current());
  }

  /** Marks a notice as read for its authorized recipient. */
  @PostMapping("/notifications/{id}/read")
  public Object read(@PathVariable String id, @RequestHeader("Idempotency-Key") String key) {
    return command("NOTIFICATION_READ", map("id", id), key);
  }

  /** Queues a retry for a failed notification delivery. */
  @PostMapping("/notification-tasks/{id}/retry")
  public Object retry(@PathVariable String id, @RequestHeader("Idempotency-Key") String key) {
    return command("TASK_RETRY", map("id", id), key);
  }

  /** Records an administrator's resolution of a work item. */
  @PostMapping("/work-items/{id}/resolution")
  public Object resolve(
      @PathVariable String id,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody Map<String, Object> b) {
    b.put("id", id);
    return command("WORK_RESOLVE", b, key);
  }

  /** Lists scoped audit records matching the request filters. */
  @GetMapping("/audit")
  public Object audit(
      @RequestParam(required = false) String object,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to) {
    return audit.list(access.current(), object, from, to);
  }

  /** Returns the lodging report for the requested scope and date basis. */
  @GetMapping("/reports/stays")
  public Object report(
      @RequestParam String basis,
      @RequestParam LocalDate from,
      @RequestParam LocalDate to,
      @RequestParam(required = false) String hotelId) {
    return reports.report(access.current(), basis, from, to, hotelId);
  }

  /** Downloads the same scoped lodging report as a UTF-8 CSV file. */
  @GetMapping("/reports/stays.csv")
  public ResponseEntity<byte[]> csv(
      @RequestParam String basis,
      @RequestParam LocalDate from,
      @RequestParam LocalDate to,
      @RequestParam(required = false) String hotelId) {
    var r = reports.report(access.current(), basis, from, to, hotelId);
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=stay-report.csv")
        .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
        .body(reports.csv(r).getBytes(StandardCharsets.UTF_8));
  }

  private ResponseEntity<?> command(String type, Map<String, Object> b, String key) {
    var result = commands.submit(access.current(), type, b, key);
    return ResponseEntity.status(integer(result, "httpStatus")).body(result);
  }
}
