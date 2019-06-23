package retry

import cats.{Apply, Applicative}
import cats.kernel.BoundedSemilattice
import retry.PolicyDecision._

import scala.concurrent.duration.Duration
import cats.Apply

case class RetryPolicy[M[_]](
    decideNextRetry: RetryStatus => M[PolicyDecision]) {
  def followedBy(rp: RetryPolicy[M])(implicit M: Apply[M]): RetryPolicy[M] =
    RetryPolicy(status =>
      M.map2(decideNextRetry(status), rp.decideNextRetry(status)) {
        case (GiveUp, pd) => pd
        case (pd, _)      => pd
    })

  /**
    * Combine this schedule with another schedule, giving up when either of the schedules want to give up
    * and choosing the maximum of the two delays when both of the schedules want to delay the next retry.
    * The dual of the `meet` operation.
    */
  def join(rp: RetryPolicy[M])(implicit M: Apply[M]): RetryPolicy[M] =
    RetryPolicy[M](status =>
      M.map2(decideNextRetry(status), rp.decideNextRetry(status)) {
        case (DelayAndRetry(a), DelayAndRetry(b)) => DelayAndRetry(a max b)
        case _                                    => GiveUp
    })

  /**
    * Combine this schedule with another schedule, giving up when both of the schedules want to give up
    * and choosing the minimum of the two delays when both of the schedules want to delay the next retry.
    * The dual of the `join` operation.
    */
  def meet(rp: RetryPolicy[M])(implicit M: Apply[M]): RetryPolicy[M] =
    RetryPolicy[M](status =>
      M.map2(decideNextRetry(status), rp.decideNextRetry(status)) {
        case (DelayAndRetry(a), DelayAndRetry(b)) => DelayAndRetry(a min b)
        case (s @ DelayAndRetry(_), GiveUp)       => s
        case (GiveUp, s @ DelayAndRetry(_))       => s
        case _                                    => GiveUp
    })
}

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
      ): RetryPolicy[M] = x.join(y)
    }

}
