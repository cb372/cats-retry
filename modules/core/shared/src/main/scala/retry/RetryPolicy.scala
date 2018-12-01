package retry

import cats.Applicative
import cats.kernel.BoundedSemilattice
import retry.PolicyDecision._

import scala.concurrent.duration.Duration

case class RetryPolicy[M[_]](decideNextRetry: RetryStatus => M[PolicyDecision])

object RetryPolicy {

  def lift[M[_]](
      f: RetryStatus => PolicyDecision
  )(
      implicit
      M: Applicative[M]
  ): RetryPolicy[M] =
    RetryPolicy[M](decideNextRetry = retryStatus => M.pure(f(retryStatus)))

  implicit def boundedSemilatticeForRetryPolicy[M[_]](
      implicit M: Applicative[M]
  ): BoundedSemilattice[RetryPolicy[M]] =
    new BoundedSemilattice[RetryPolicy[M]] {

      override def empty: RetryPolicy[M] =
        RetryPolicies.constantDelay[M](Duration.Zero)

      override def combine(
          x: RetryPolicy[M],
          y: RetryPolicy[M]
      ): RetryPolicy[M] = RetryPolicy[M](
        retryStatus =>
          M.map2(
            x.decideNextRetry(retryStatus),
            y.decideNextRetry(retryStatus)
          ) {
            case (DelayAndRetry(a), DelayAndRetry(b)) => DelayAndRetry(a max b)
            case _                                    => GiveUp
        }
      )
    }

}
