package cn.edu.campus.hospitality.notification;

import cn.edu.campus.hospitality.shared.CommandService;
import cn.edu.campus.hospitality.stay.StayService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Schedules notification delivery, request recovery, and arrival reminders. */
@Component
@ConditionalOnProperty(name = "app.scheduling", havingValue = "true", matchIfMissing = true)
public class BackgroundJobs {
  private final NotificationService notices;
  private final StayService stays;
  private final CommandService commands;
  private final TransactionTemplate tx;

  /** Creates the scheduler using the command, notification, and stay services. */
  public BackgroundJobs(
      NotificationService notices,
      StayService stays,
      CommandService commands,
      PlatformTransactionManager manager) {
    this.notices = notices;
    this.stays = stays;
    this.commands = commands;
    this.tx = new TransactionTemplate(manager);
  }

  /** Processes notification tasks that are ready for delivery. */
  @Scheduled(fixedDelay = 15000, initialDelay = 20000)
  public void notices() {
    notices.deliver();
  }

  /** Recovers saved operation requests that still need processing. */
  @Scheduled(fixedDelay = 30000, initialDelay = 30000)
  public void requests() {
    commands.recover();
  }

  /** Checks for outstanding arrival reminders in a transaction. */
  @Scheduled(fixedDelay = 60000, initialDelay = 10000)
  public void reminders() {
    tx.executeWithoutResult(s -> stays.arrivalReminders());
  }
}
