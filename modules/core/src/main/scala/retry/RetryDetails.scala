package retry

import scala.concurrent.duration.FiniteDuration

sealed trait RetryDetails

object RetryDetails {

  case class GivingUp(totalRetries: Int, totalDelay: FiniteDuration)
      extends RetryDetails

  case class WillDelayAndRetry(nextDelay: FiniteDuration,
                               retriesSoFar: Int,
                               cumulativeDelay: FiniteDuration)
      extends RetryDetails

}
