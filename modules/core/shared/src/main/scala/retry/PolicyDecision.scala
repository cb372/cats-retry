package retry

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration.DurationInt

enum PolicyDecision(val delay: FiniteDuration):
  case GiveUp                                            extends PolicyDecision(0.seconds)
  case DelayAndRetry(override val delay: FiniteDuration) extends PolicyDecision(delay)
