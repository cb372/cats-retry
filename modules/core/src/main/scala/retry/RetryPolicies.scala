package retry

import java.util.concurrent.TimeUnit

import cats.{Applicative, Monad}
import retry.PolicyDecision._

import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.util.Random

object RetryPolicies {

  def constantDelay[M[_]: Applicative](delay: FiniteDuration): RetryPolicy[M] =
    RetryPolicy.lift(_ => DelayAndRetry(delay))

  def exponentialBackoff[M[_]: Applicative](
      baseDelay: FiniteDuration): RetryPolicy[M] =
    RetryPolicy.lift { status =>
      DelayAndRetry(baseDelay * Math.pow(2, status.retriesSoFar).toLong)
    }

  def limitRetries[M[_]: Applicative](maxRetries: Int): RetryPolicy[M] =
    RetryPolicy.lift { status =>
      if (status.retriesSoFar >= maxRetries) {
        GiveUp
      } else {
        DelayAndRetry(Duration.Zero)
      }
    }

  def fibonacciBackoff[M[_]: Applicative](
      baseDelay: FiniteDuration): RetryPolicy[M] =
    RetryPolicy.lift { status =>
      val delay = baseDelay * Fibonacci.fibonacci(status.retriesSoFar + 1)
      DelayAndRetry(delay)
    }

  /*
  See https://aws.amazon.com/blogs/architecture/exponential-backoff-and-jitter/
   */
  def fullJitter[M[_]: Applicative](baseDelay: FiniteDuration): RetryPolicy[M] =
    RetryPolicy.lift { status =>
      val e          = Math.pow(2, status.retriesSoFar).toLong
      val maxDelay   = baseDelay * e
      val delayNanos = (maxDelay.toNanos * Random.nextDouble()).toLong
      DelayAndRetry(new FiniteDuration(delayNanos, TimeUnit.NANOSECONDS))
    }

  // TODO capDelay, limitRetriesByDelay, limitRetriesByCumulativeDelay

}
