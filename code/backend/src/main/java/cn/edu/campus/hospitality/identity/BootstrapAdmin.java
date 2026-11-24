package cn.edu.campus.hospitality.identity;

import static cn.edu.campus.hospitality.shared.Data.id;
import static cn.edu.campus.hospitality.shared.Data.map;
import static cn.edu.campus.hospitality.shared.Data.require;

import cn.edu.campus.hospitality.shared.Db;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Initializes the first administrator in a non-demo installation. */
@Component
@ConditionalOnProperty(name = "app.demo", havingValue = "false")
public class BootstrapAdmin implements CommandLineRunner {
  private final Db db;
  private final PasswordEncoder passwords;
  private final String username;
  private final String password;

  /** Receives the configured administrator credentials and password encoder. */
  public BootstrapAdmin(
      Db db,
      PasswordEncoder passwords,
      @Value("${BOOTSTRAP_ADMIN_USERNAME:admin}") String username,
      @Value("${BOOTSTRAP_ADMIN_PASSWORD:}") String password) {
    this.db = db;
    this.passwords = passwords;
    this.username = username;
    this.password = password;
  }

  @Override
  @Transactional
  public void run(String... args) {
    if (db.count("SELECT COUNT(*) FROM app_user") > 0) {
      return;
    }
    require(
        password.length() >= 10,
        "BOOTSTRAP_PASSWORD_REQUIRED",
        "首次正式运行请设置至少10位的 BOOTSTRAP_ADMIN_PASSWORD");
    db.insert(
        "app_user",
        map(
            "id",
            id(),
            "username",
            username,
            "display_name",
            "平台管理员",
            "password_hash",
            passwords.encode(password),
            "role",
            "ADMIN",
            "enabled",
            true));
  }
}
