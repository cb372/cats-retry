package retry

import cats.Monad
import cats.kernel.Monoid
import retry.PolicyDecision._

import scala.concurrent.duration.Duration

case class RetryPolicy[M[_]: Monad](
    decideNextRetry: RetryStatus => M[PolicyDecision])

object RetryPolicy {

  def lift[M[_]](f: RetryStatus => PolicyDecision)(
      implicit M: Monad[M]): RetryPolicy[M] =
    RetryPolicy[M](decideNextRetry = retryStatus => M.pure(f(retryStatus)))

  implicit def monoidForRetryPolicy[M[_]](
      implicit M: Monad[M]): Monoid[RetryPolicy[M]] =
    new Monoid[RetryPolicy[M]] {

      override def empty: RetryPolicy[M] =
        RetryPolicies.constantDelay(Duration.Zero)

      override def combine(x: RetryPolicy[M],
                           y: RetryPolicy[M]): RetryPolicy[M] = RetryPolicy(
        decideNextRetry = { retryStatus =>
          M.map2(x.decideNextRetry(retryStatus),
                  y.decideNextRetry(retryStatus)) {
              case (DelayAndRetry(a), DelayAndRetry(b)) =>
                DelayAndRetry(a max b)
              case _ => GiveUp
            }
        }
      )
    }

}
