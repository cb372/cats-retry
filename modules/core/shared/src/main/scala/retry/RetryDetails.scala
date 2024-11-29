package retry

import scala.concurrent.duration.FiniteDuration

/** A collection of information about retries that could be useful for logging
  *
  * @param retriesSoFar
  *   How many retries have occurred so far. Will be zero or more. The total number of attempts so far is one
  *   more than this number.
  * @param cumulativeDelay
  *   The total time we have spent delaying between retries so far.
  * @param nextStepIfUnsuccessful
  *   What the retry policy has chosen to do next if the latest attempt is not successful.
  */
case class RetryDetails(
    retriesSoFar: Int,
    cumulativeDelay: FiniteDuration,
    nextStepIfUnsuccessful: RetryDetails.NextStep
)

object RetryDetails:

  enum NextStep:
    case GiveUp
    case DelayAndRetry(nextDelay: FiniteDuration)
