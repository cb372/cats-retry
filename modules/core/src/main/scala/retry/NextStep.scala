package retry

import scala.concurrent.duration.FiniteDuration

// TODO this should be internal, make a separate user-facing ADT (FailureDetails?) for passing to the logging handler

sealed trait NextStep

case class WillGiveUp(finalRetryStatus: RetryStatus) extends NextStep

case class WillRetryAfterDelay(delay: FiniteDuration,
                               previousStatus: RetryStatus,
                               updatedStatus: RetryStatus)
    extends NextStep
