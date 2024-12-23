package retry

import cats.{Apply, Applicative, Monad, Functor}
import cats.kernel.BoundedSemilattice
import retry.PolicyDecision.*

import scala.concurrent.duration.Duration
import scala.concurrent.duration.FiniteDuration
import cats.arrow.FunctionK
import cats.implicits.*
import cats.Show

case class RetryPolicy[F[_]](
    decideNextRetry: RetryStatus => F[PolicyDecision]
):
  def show: String = toString

  def followedBy(rp: RetryPolicy[F])(using F: Apply[F]): RetryPolicy[F] =
    RetryPolicy.withShow(
      status =>
        F.map2(decideNextRetry(status), rp.decideNextRetry(status)) {
          case (GiveUp, pd) => pd
          case (pd, _)      => pd
        },
      show"$show.followedBy($rp)"
    )

  /** Combine this schedule with another schedule, giving up when either of the schedules want to give up and
    * choosing the maximum of the two delays when both of the schedules want to delay the next retry. The dual
    * of the `meet` operation.
    */
  def join(rp: RetryPolicy[F])(using F: Apply[F]): RetryPolicy[F] =
    RetryPolicy.withShow[F](
      status =>
        F.map2(decideNextRetry(status), rp.decideNextRetry(status)) {
          case (DelayAndRetry(a), DelayAndRetry(b)) => DelayAndRetry(a max b)
          case _                                    => GiveUp
        },
      show"$show.join($rp)"
    )

  /** Combine this schedule with another schedule, giving up when both of the schedules want to give up and
    * choosing the minimum of the two delays when both of the schedules want to delay the next retry. The dual
    * of the `join` operation.
    */
  def meet(rp: RetryPolicy[F])(using F: Apply[F]): RetryPolicy[F] =
    RetryPolicy.withShow[F](
      status =>
        F.map2(decideNextRetry(status), rp.decideNextRetry(status)) {
          case (DelayAndRetry(a), DelayAndRetry(b)) => DelayAndRetry(a min b)
          case (s @ DelayAndRetry(_), GiveUp)       => s
          case (GiveUp, s @ DelayAndRetry(_))       => s
          case _                                    => GiveUp
        },
      show"$show.meet($rp)"
    )

  def mapDelay(
      f: FiniteDuration => FiniteDuration
  )(using F: Functor[F]): RetryPolicy[F] =
    RetryPolicy.withShow(
      status =>
        F.map(decideNextRetry(status)) {
          case GiveUp           => GiveUp
          case DelayAndRetry(d) => DelayAndRetry(f(d))
        },
      show"$show.mapDelay(<function>)"
    )

  def flatMapDelay(
      f: FiniteDuration => F[FiniteDuration]
  )(using F: Monad[F]): RetryPolicy[F] =
    RetryPolicy.withShow(
      status =>
        F.flatMap(decideNextRetry(status)) {
          case GiveUp           => F.pure(GiveUp)
          case DelayAndRetry(d) => F.map(f(d))(DelayAndRetry(_))
        },
      show"$show.flatMapDelay(<function>)"
    )

  def mapK[N[_]](nt: FunctionK[F, N]): RetryPolicy[N] =
    RetryPolicy.withShow(
      status => nt(decideNextRetry(status)),
      show"$show.mapK(<FunctionK>)"
    )
end RetryPolicy

object RetryPolicy:
  def lift[F[_]](
      f: RetryStatus => PolicyDecision
  )(using
      F: Applicative[F]
  ): RetryPolicy[F] =
    RetryPolicy[F](decideNextRetry = retryStatus => F.pure(f(retryStatus)))

  def withShow[F[_]](
      decideNextRetry: RetryStatus => F[PolicyDecision],
      pretty: => String
  ): RetryPolicy[F] =
    new RetryPolicy[F](decideNextRetry):
      override def show: String     = pretty
      override def toString: String = pretty

  def liftWithShow[F[_]: Applicative](
      decideNextRetry: RetryStatus => PolicyDecision,
      pretty: => String
  ): RetryPolicy[F] =
    withShow(rs => Applicative[F].pure(decideNextRetry(rs)), pretty)

  given [F[_]](using
      F: Applicative[F]
  ): BoundedSemilattice[RetryPolicy[F]] =
    new BoundedSemilattice[RetryPolicy[F]]:
      override def empty: RetryPolicy[F] =
        RetryPolicies.constantDelay[F](Duration.Zero)

      override def combine(
          x: RetryPolicy[F],
          y: RetryPolicy[F]
      ): RetryPolicy[F] = x.join(y)

  given [F[_]]: Show[RetryPolicy[F]] =
    Show.show(_.show)
end RetryPolicy
