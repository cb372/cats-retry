package retry

import scala.concurrent.duration.FiniteDuration

sealed trait RetryDetails {
  def retriesSoFar: Int
  def cumulativeDelay: FiniteDuration
}

object RetryDetails {
  final case class GivingUp(
      totalRetries: Int,
      totalDelay: FiniteDuration
  ) extends RetryDetails {
    val retriesSoFar: Int               = totalRetries
    val cumulativeDelay: FiniteDuration = totalDelay
  }

  final case class WillDelayAndRetry(
      nextDelay: FiniteDuration,
      retriesSoFar: Int,
      cumulativeDelay: FiniteDuration
  ) extends RetryDetails
}
