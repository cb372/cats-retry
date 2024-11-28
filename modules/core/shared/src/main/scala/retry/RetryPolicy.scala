package retry

import cats.{Apply, Applicative, Monad, Functor}
import cats.kernel.BoundedSemilattice
import retry.PolicyDecision.*

import scala.concurrent.duration.Duration
import scala.concurrent.duration.FiniteDuration
import cats.arrow.FunctionK
import cats.implicits.*
import cats.Show

case class RetryPolicy[M[_]](
    decideNextRetry: RetryStatus => M[PolicyDecision]
):
  def show: String = toString

  def followedBy(rp: RetryPolicy[M])(using M: Apply[M]): RetryPolicy[M] =
    RetryPolicy.withShow(
      status =>
        M.map2(decideNextRetry(status), rp.decideNextRetry(status)) {
          case (GiveUp, pd) => pd
          case (pd, _)      => pd
        },
      show"$show.followedBy($rp)"
    )

  /** Combine this schedule with another schedule, giving up when either of the schedules want to give up and
    * choosing the maximum of the two delays when both of the schedules want to delay the next retry. The dual
    * of the `meet` operation.
    */
  def join(rp: RetryPolicy[M])(using M: Apply[M]): RetryPolicy[M] =
    RetryPolicy.withShow[M](
      status =>
        M.map2(decideNextRetry(status), rp.decideNextRetry(status)) {
          case (DelayAndRetry(a), DelayAndRetry(b)) => DelayAndRetry(a max b)
          case _                                    => GiveUp
        },
      show"$show.join($rp)"
    )

  /** Combine this schedule with another schedule, giving up when both of the schedules want to give up and
    * choosing the minimum of the two delays when both of the schedules want to delay the next retry. The dual
    * of the `join` operation.
    */
  def meet(rp: RetryPolicy[M])(using M: Apply[M]): RetryPolicy[M] =
    RetryPolicy.withShow[M](
      status =>
        M.map2(decideNextRetry(status), rp.decideNextRetry(status)) {
          case (DelayAndRetry(a), DelayAndRetry(b)) => DelayAndRetry(a min b)
          case (s @ DelayAndRetry(_), GiveUp)       => s
          case (GiveUp, s @ DelayAndRetry(_))       => s
          case _                                    => GiveUp
        },
      show"$show.meet($rp)"
    )

  def mapDelay(
      f: FiniteDuration => FiniteDuration
  )(using M: Functor[M]): RetryPolicy[M] =
    RetryPolicy.withShow(
      status =>
        M.map(decideNextRetry(status)) {
          case GiveUp           => GiveUp
          case DelayAndRetry(d) => DelayAndRetry(f(d))
        },
      show"$show.mapDelay(<function>)"
    )

  def flatMapDelay(
      f: FiniteDuration => M[FiniteDuration]
  )(using M: Monad[M]): RetryPolicy[M] =
    RetryPolicy.withShow(
      status =>
        M.flatMap(decideNextRetry(status)) {
          case GiveUp           => M.pure(GiveUp)
          case DelayAndRetry(d) => M.map(f(d))(DelayAndRetry(_))
        },
      show"$show.flatMapDelay(<function>)"
    )

  def mapK[N[_]](nt: FunctionK[M, N]): RetryPolicy[N] =
    RetryPolicy.withShow(
      status => nt(decideNextRetry(status)),
      show"$show.mapK(<FunctionK>)"
    )
end RetryPolicy

object RetryPolicy:
  def lift[M[_]](
      f: RetryStatus => PolicyDecision
  )(using
      M: Applicative[M]
  ): RetryPolicy[M] =
    RetryPolicy[M](decideNextRetry = retryStatus => M.pure(f(retryStatus)))

  def withShow[M[_]](
      decideNextRetry: RetryStatus => M[PolicyDecision],
      pretty: => String
  ): RetryPolicy[M] =
    new RetryPolicy[M](decideNextRetry):
      override def show: String     = pretty
      override def toString: String = pretty

  def liftWithShow[M[_]: Applicative](
      decideNextRetry: RetryStatus => PolicyDecision,
      pretty: => String
  ): RetryPolicy[M] =
    withShow(rs => Applicative[M].pure(decideNextRetry(rs)), pretty)

  given [M[_]](using
      M: Applicative[M]
  ): BoundedSemilattice[RetryPolicy[M]] =
    new BoundedSemilattice[RetryPolicy[M]]:
      override def empty: RetryPolicy[M] =
        RetryPolicies.constantDelay[M](Duration.Zero)

      override def combine(
          x: RetryPolicy[M],
          y: RetryPolicy[M]
      ): RetryPolicy[M] = x.join(y)

  given [M[_]]: Show[RetryPolicy[M]] =
    Show.show(_.show)
end RetryPolicy
