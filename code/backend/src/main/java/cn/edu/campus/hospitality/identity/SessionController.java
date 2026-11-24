package cn.edu.campus.hospitality.identity;

import static cn.edu.campus.hospitality.shared.Data.map;

import cn.edu.campus.hospitality.shared.BusinessClock;
import cn.edu.campus.hospitality.shared.BusinessException;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Exposes session state and the local application health endpoint. */
@RestController
@RequestMapping("/api")
public class SessionController {
  private final Access access;
  private final BusinessClock clock;

  /** Creates the session controller with the current actor and business clock services. */
  public SessionController(Access access, BusinessClock clock) {
    this.access = access;
    this.clock = clock;
  }

  /** Returns session and CSRF details, clearing a session whose account is no longer active. */
  @GetMapping("/session")
  public Map<String, Object> session(
      Authentication auth, CsrfToken csrf, jakarta.servlet.http.HttpServletRequest request) {
    Map<String, Object> user = null;
    if (auth != null) {
      try {
        user = access.current().view();
      } catch (BusinessException ex) {
        if (ex.httpStatus() != 401) {
          throw ex;
        }
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        var old = request.getSession(false);
        if (old != null) {
          old.removeAttribute(
              org.springframework.security.web.context.HttpSessionSecurityContextRepository
                  .SPRING_SECURITY_CONTEXT_KEY);
        }
      }
    }
    return map(
        "user",
        user,
        "csrfToken",
        csrf.getToken(),
        "csrfHeader",
        csrf.getHeaderName(),
        "today",
        clock.today(),
        "now",
        clock.now());
  }

  /** Reports that the HTTP service can respond. */
  @GetMapping("/health")
  public Map<String, Object> health() {
    return map("status", "UP");
  }
}
