package cn.edu.campus.hospitality;

import java.io.IOException;
import java.io.OutputStream;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.desktop", havingValue = "true")
final class DesktopLifecycle {
  private final ServletWebServerApplicationContext context;

  DesktopLifecycle(ServletWebServerApplicationContext context) {
    this.context = context;
  }

  @EventListener(ApplicationReadyEvent.class)
  void connectToLauncher() {
    System.out.println("CAMPUS_HOSPITALITY_READY=" + context.getWebServer().getPort());
    System.out.flush();
    Thread.ofVirtual().name("desktop-launcher").start(this::waitForLauncherExit);
  }

  private void waitForLauncherExit() {
    try {
      System.in.transferTo(OutputStream.nullOutputStream());
    } catch (IOException ignored) {
      // A broken launcher pipe has the same meaning as end of input.
    } finally {
      context.close();
    }
  }
}
