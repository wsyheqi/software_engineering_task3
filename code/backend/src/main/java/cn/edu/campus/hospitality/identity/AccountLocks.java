package cn.edu.campus.hospitality.identity;

import static cn.edu.campus.hospitality.shared.Data.bool;

import cn.edu.campus.hospitality.shared.BusinessException;
import cn.edu.campus.hospitality.shared.Db;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Account lifecycle changes and business writes must observe the same account lock. */
@Component
public class AccountLocks {
  private final Db db;
  private final boolean h2;

  /** Selects account lock behavior for the configured database. */
  public AccountLocks(Db db, @Value("${spring.datasource.url}") String url) {
    this.db = db;
    this.h2 = url.startsWith("jdbc:h2:");
  }

  /** Locks and rechecks an actor's active account in the current transaction. */
  public Actor active(Actor actor) {
    // A consistent order prevents two administrators from deleting/disabling each other.
    if (actor.is("ADMIN")) {
      db.rows("SELECT id FROM app_user WHERE role='ADMIN' ORDER BY id FOR UPDATE");
    }
    var row =
        db.one(
            "SELECT * FROM app_user WHERE id=? "
                + (actor.is("ADMIN") || h2 ? "FOR UPDATE" : "FOR SHARE"),
            actor.id());
    if (!bool(row, "enabled") || row.get("deletedAt") != null) {
      throw new BusinessException("UNAUTHENTICATED", "账号已停用或删除，请重新登录", Map.of(), 401);
    }
    return Actor.from(row);
  }
}
