package retry

import java.util.concurrent.TimeUnit

import cats.Applicative
import cats.syntax.functor._
import retry.PolicyDecision._

import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.util.Random

object RetryPolicies {

  /**
    * Delay by a constant amount before each retry. Never give up.
    */
  def constantDelay[M[_]: Applicative](delay: FiniteDuration): RetryPolicy[M] =
    RetryPolicy.lift(_ => DelayAndRetry(delay))

  /**
    * Each delay is twice as long as the previous one. Never give up.
    */
  def exponentialBackoff[M[_]: Applicative](
      baseDelay: FiniteDuration): RetryPolicy[M] =
    RetryPolicy.lift { status =>
      DelayAndRetry(baseDelay * Math.pow(2, status.retriesSoFar).toLong)
    }

  /**
    * Retry without delay, giving up after the given number of retries.
    */
  def limitRetries[M[_]: Applicative](maxRetries: Int): RetryPolicy[M] =
    RetryPolicy.lift { status =>
      if (status.retriesSoFar >= maxRetries) {
        GiveUp
      } else {
        DelayAndRetry(Duration.Zero)
      }
    }

  /**
    * Delay(n) = Delay(n - 2) + Delay(n - 1)
    *
    * e.g. if `baseDelay` is 10 milliseconds, the delays before each retry will be
    * 10 ms, 10 ms, 20 ms, 30ms, 50ms, 80ms, 130ms, ...
    */
  def fibonacciBackoff[M[_]: Applicative](
      baseDelay: FiniteDuration): RetryPolicy[M] =
    RetryPolicy.lift { status =>
      val delay = baseDelay * Fibonacci.fibonacci(status.retriesSoFar + 1)
      DelayAndRetry(delay)
    }

  /**
    * "Full jitter" backoff algorithm.
    * See https://aws.amazon.com/blogs/architecture/exponential-backoff-and-jitter/
    */
  def fullJitter[M[_]: Applicative](baseDelay: FiniteDuration): RetryPolicy[M] =
    RetryPolicy.lift { status =>
      val e          = Math.pow(2, status.retriesSoFar).toLong
      val maxDelay   = baseDelay * e
      val delayNanos = (maxDelay.toNanos * Random.nextDouble()).toLong
      DelayAndRetry(new FiniteDuration(delayNanos, TimeUnit.NANOSECONDS))
    }

  /**
    * Set an upper bound on any individual delay produced by the given policy.
    */
  def capDelay[M[_]: Applicative](cap: FiniteDuration,
                                  policy: RetryPolicy[M]): RetryPolicy[M] = {

    def decideNextRetry(status: RetryStatus): M[PolicyDecision] =
      policy.decideNextRetry(status).map {
        case DelayAndRetry(delay) => DelayAndRetry(delay min cap)
        case GiveUp               => GiveUp
      }

    RetryPolicy[M](decideNextRetry)
  }

  /**
    * Add an upper bound to a policy such that once the given time-delay
    * amount <b>per try</b> has been reached or exceeded, the policy will stop
    * retrying and give up. If you need to stop retrying once <b>cumulative</b>
    * delay reaches a time-delay amount, use [[limitRetriesByCumulativeDelay]].
    */
  def limitRetriesByDelay[M[_]: Applicative](
      threshold: FiniteDuration,
      policy: RetryPolicy[M]): RetryPolicy[M] = {

    def decideNextRetry(status: RetryStatus): M[PolicyDecision] =
      policy.decideNextRetry(status).map {
        case r @ DelayAndRetry(delay) =>
          if (delay > threshold) GiveUp else r
        case GiveUp => GiveUp
      }

    RetryPolicy[M](decideNextRetry)
  }

  /**
    * Add an upperbound to a policy such that once the cumulative delay
    * over all retries has reached or exceeded the given limit, the
    * policy will stop retrying and give up.
    */
  def limitRetriesByCumulativeDelay[M[_]: Applicative](
      threshold: FiniteDuration,
      policy: RetryPolicy[M]): RetryPolicy[M] = {

    def decideNextRetry(status: RetryStatus): M[PolicyDecision] =
      policy.decideNextRetry(status).map {
        case r @ DelayAndRetry(delay) =>
          if (status.cumulativeDelay + delay >= threshold) GiveUp else r
        case GiveUp => GiveUp
      }

    RetryPolicy[M](decideNextRetry)
  }

}
