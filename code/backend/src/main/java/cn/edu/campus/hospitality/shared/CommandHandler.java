package cn.edu.campus.hospitality.shared;

import cn.edu.campus.hospitality.identity.Actor;
import java.util.Map;
import java.util.Set;

/** Executes a group of registered business commands within the command transaction. */
public interface CommandHandler {
  /** Returns the operation codes supported by this handler. */
  Set<String> actions();

  /** Executes one registered operation on behalf of the authenticated actor. */
  Map<String, Object> handle(Actor actor, String action, Map<String, Object> body);
}
