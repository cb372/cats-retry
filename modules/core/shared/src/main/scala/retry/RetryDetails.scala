package retry

import scala.concurrent.duration.FiniteDuration

enum RetryDetails(
    val retriesSoFar: Int,
    val cumulativeDelay: FiniteDuration,
    val givingUp: Boolean,
    val upcomingDelay: Option[FiniteDuration]
):

  case GivingUp(
      totalRetries: Int,
      totalDelay: FiniteDuration
  ) extends RetryDetails(
        retriesSoFar = totalRetries,
        cumulativeDelay = totalDelay,
        givingUp = true,
        upcomingDelay = None
      )

  case WillDelayAndRetry(
      nextDelay: FiniteDuration,
      override val retriesSoFar: Int,
      override val cumulativeDelay: FiniteDuration
  ) extends RetryDetails(
        retriesSoFar = retriesSoFar,
        cumulativeDelay = cumulativeDelay,
        givingUp = false,
        upcomingDelay = Some(nextDelay)
      )
