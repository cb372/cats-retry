package retry

import scala.concurrent.duration.FiniteDuration

sealed trait RetryDetails

object RetryDetails {

  final case class GivingUp(
      totalRetries: Int,
      totalDelay: FiniteDuration
  ) extends RetryDetails

  final case class WillDelayAndRetry(
      nextDelay: FiniteDuration,
      retriesSoFar: Int,
      cumulativeDelay: FiniteDuration
  ) extends RetryDetails

}
