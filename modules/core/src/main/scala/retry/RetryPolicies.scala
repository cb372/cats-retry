package retry

import cats.Monad
import retry.PolicyDecision._

import scala.concurrent.duration.{Duration, FiniteDuration}

object RetryPolicies {

  def constantDelay[M[_]: Monad](delay: FiniteDuration): RetryPolicy[M] =
    RetryPolicy.lift(_ => DelayAndRetry(delay))

  def exponentialBackoff[M[_]: Monad](
      baseDelay: FiniteDuration): RetryPolicy[M] =
    RetryPolicy.lift { status =>
      DelayAndRetry(baseDelay * Math.pow(2, status.retriesSoFar).toLong)
    }

  def limitRetries[M[_]: Monad](maxRetries: Int): RetryPolicy[M] =
    RetryPolicy.lift { status =>
      if (status.retriesSoFar >= maxRetries) {
        GiveUp
      } else {
        DelayAndRetry(Duration.Zero)
      }
    }

  // TODO fibonacci, jitter, capDelay, limitRetriesByDelay, limitRetriesByCumulativeDelay

}
