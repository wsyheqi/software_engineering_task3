package cn.edu.campus.hospitality.booking.domain;

import static cn.edu.campus.hospitality.shared.Data.require;

import java.util.Set;

/** Defines the permitted statuses and transitions for a reservation. */
public enum OrderState {
  PENDING,
  CONFIRMED,
  CHECKED_IN,
  CHECKED_OUT,
  CANCELLED,
  FAILED,
  NO_SHOW,
  RELOCATING,
  CLOSED;

  /** Rejects a transition if this order status cannot reach the requested target. */
  public void transitionTo(OrderState target) {
    Set<OrderState> allowed =
        switch (this) {
          case PENDING -> Set.of(CONFIRMED, FAILED);
          case CONFIRMED -> Set.of(CHECKED_IN, CANCELLED, NO_SHOW, RELOCATING);
          case CHECKED_IN -> Set.of(CHECKED_OUT, RELOCATING);
          case RELOCATING -> Set.of(CLOSED);
          default -> Set.of();
        };
    require(allowed.contains(target), "STATE_CONFLICT", "不允许从 " + this + " 转换为 " + target);
  }
}
