package cn.edu.campus.hospitality.identity;

import cn.edu.campus.hospitality.audit.AuditService;
import cn.edu.campus.hospitality.shared.Data;
import cn.edu.campus.hospitality.shared.Db;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

/** Configures session authentication, request authorization, and CSRF checks. */
@Configuration
public class SecurityConfig {
  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  UserDetailsService users(Db db) {
    return username ->
        db.find("SELECT * FROM app_user WHERE username=? AND deleted_at IS NULL", username)
            .map(
                row ->
                    User.withUsername(username)
                        .password(Data.str(row, "passwordHash"))
                        .roles(Data.str(row, "role"))
                        .disabled(!Data.bool(row, "enabled"))
                        .build())
            .orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
  }

  @Bean
  SecurityFilterChain security(HttpSecurity http, AuditService audit, Db db) throws Exception {
    http.authorizeHttpRequests(
        a ->
            a.requestMatchers(
                    "/", "/index.html", "/assets/**", "/favicon.svg", "/api/session", "/api/health")
                .permitAll()
                .anyRequest()
                .authenticated());
    http.formLogin(
        f ->
            f.loginProcessingUrl("/api/session")
                .successHandler((request, response, auth) -> json(response, 200, "{\"ok\":true}"))
                .failureHandler(
                    (request, response, error) -> {
                      audit.denied(null, "LOGIN", null, null, "登录失败");
                      json(
                          response,
                          401,
                          "{\"businessCode\":\"INVALID_CREDENTIALS\",\"message\":\"账号或密码不正确\"}");
                    }));
    http.logout(
        l ->
            l.logoutRequestMatcher(new AntPathRequestMatcher("/api/session", "DELETE"))
                .logoutSuccessHandler((req, res, auth) -> json(res, 200, "{\"ok\":true}")));
    http.exceptionHandling(
        e ->
            e.authenticationEntryPoint(
                    (req, res, ex) ->
                        json(
                            res,
                            401,
                            "{\"businessCode\":\"UNAUTHENTICATED\",\"message\":\"请先登录\"}"))
                .accessDeniedHandler(
                    (req, res, ex) -> {
                      var auth =
                          org.springframework.security.core.context.SecurityContextHolder
                              .getContext()
                              .getAuthentication();
                      Actor actor =
                          auth == null
                              ? null
                              : db.find("SELECT * FROM app_user WHERE username=?", auth.getName())
                                  .map(Actor::from)
                                  .orElse(null);
                      audit.denied(
                          actor, "HTTP", actor == null ? null : actor.hotelId(), null, "请求被安全策略拒绝");
                      json(
                          res,
                          403,
                          "{\"businessCode\":\"FORBIDDEN\",\"message\":\"请求无权限或会话校验已过期，请刷新后重试\"}");
                    }));
    return http.build();
  }

  private static void json(HttpServletResponse response, int status, String content)
      throws java.io.IOException {
    response.setStatus(status);
    response.setContentType("application/json;charset=UTF-8");
    response.getWriter().write(content);
  }
}
