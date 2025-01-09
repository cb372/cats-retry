package retry

import cats.{Apply, Applicative, Monad, Functor}
import cats.kernel.BoundedSemilattice
import retry.PolicyDecision.*

import scala.concurrent.duration.Duration
import scala.concurrent.duration.FiniteDuration
import cats.arrow.FunctionK
import cats.implicits.*
import cats.Show

/** A retry policy that decides, after a given attempt, whether to delay and retry, or to give up.
  *
  * @param decideNextRetry
  *   A function that takes
  *   - the result of the attempt, which might be a successful value, a failed value or an error
  *   - information about the retries and delays so far and returns a decision about what to do next
  */
case class RetryPolicy[F[_], -Res](
    decideNextRetry: (Res, RetryStatus) => F[PolicyDecision]
):
  def show: String = toString

  def followedBy[R <: Res](rp: RetryPolicy[F, R])(using F: Apply[F]): RetryPolicy[F, R] =
    RetryPolicy.withShow[F, R](
      (actionResult, status) =>
        F.map2(decideNextRetry(actionResult, status), rp.decideNextRetry(actionResult, status)) {
          case (GiveUp, pd) => pd
          case (pd, _)      => pd
        },
      show"$show.followedBy($rp)"
    )

  /** Combine this schedule with another schedule, giving up when either of the schedules want to give up and
    * choosing the maximum of the two delays when both of the schedules want to delay the next retry. The dual
    * of the `meet` operation.
    */
  def join[R <: Res](rp: RetryPolicy[F, R])(using F: Apply[F]): RetryPolicy[F, R] =
    RetryPolicy.withShow[F, R](
      (actionResult, status) =>
        F.map2(decideNextRetry(actionResult, status), rp.decideNextRetry(actionResult, status)) {
          case (DelayAndRetry(a), DelayAndRetry(b)) => DelayAndRetry(a max b)
          case _                                    => GiveUp
        },
      show"$show.join($rp)"
    )

  /** Combine this schedule with another schedule, giving up when both of the schedules want to give up and
    * choosing the minimum of the two delays when both of the schedules want to delay the next retry. The dual
    * of the `join` operation.
    */
  def meet[R <: Res](rp: RetryPolicy[F, R])(using F: Apply[F]): RetryPolicy[F, R] =
    RetryPolicy.withShow[F, R](
      (actionResult, status) =>
        F.map2(decideNextRetry(actionResult, status), rp.decideNextRetry(actionResult, status)) {
          case (DelayAndRetry(a), DelayAndRetry(b)) => DelayAndRetry(a min b)
          case (s @ DelayAndRetry(_), GiveUp)       => s
          case (GiveUp, s @ DelayAndRetry(_))       => s
          case _                                    => GiveUp
        },
      show"$show.meet($rp)"
    )

  def mapDelay(
      f: FiniteDuration => FiniteDuration
  )(using F: Functor[F]): RetryPolicy[F, Res] =
    RetryPolicy.withShow(
      (actionResult, status) =>
        F.map(decideNextRetry(actionResult, status)) {
          case GiveUp           => GiveUp
          case DelayAndRetry(d) => DelayAndRetry(f(d))
        },
      show"$show.mapDelay(<function>)"
    )

  def flatMapDelay(
      f: FiniteDuration => F[FiniteDuration]
  )(using F: Monad[F]): RetryPolicy[F, Res] =
    RetryPolicy.withShow(
      (actionResult, status) =>
        F.flatMap(decideNextRetry(actionResult, status)) {
          case GiveUp           => F.pure(GiveUp)
          case DelayAndRetry(d) => F.map(f(d))(DelayAndRetry(_))
        },
      show"$show.flatMapDelay(<function>)"
    )

  def mapK[N[_]](nt: FunctionK[F, N]): RetryPolicy[N, Res] =
    RetryPolicy.withShow(
      (actionResult, status) => nt(decideNextRetry(actionResult, status)),
      show"$show.mapK(<FunctionK>)"
    )

  def contramap[R](f: R => Res): RetryPolicy[F, R] =
    RetryPolicy.withShow[F, R](
      (actionResult, status) => decideNextRetry(f(actionResult), status),
      show"$show.contramap(<function>)"
    )
end RetryPolicy

object RetryPolicy:
  def lift[F[_], Res](
      f: (Res, RetryStatus) => PolicyDecision
  )(using
      F: Applicative[F]
  ): RetryPolicy[F, Res] = RetryPolicy[F, Res](
    decideNextRetry = (actionResult, retryStatus) => F.pure(f(actionResult, retryStatus))
  )

  def withShow[F[_], Res](
      decideNextRetry: (Res, RetryStatus) => F[PolicyDecision],
      pretty: => String
  ): RetryPolicy[F, Res] =
    new RetryPolicy[F, Res](decideNextRetry):
      override def show: String     = pretty
      override def toString: String = pretty

  def liftWithShow[F[_], Res](
      decideNextRetry: (Res, RetryStatus) => PolicyDecision,
      pretty: => String
  )(using
      F: Applicative[F]
  ): RetryPolicy[F, Res] =
    withShow((actionResult, retryStatus) => F.pure(decideNextRetry(actionResult, retryStatus)), pretty)

  given [F[_], Res](using
      F: Applicative[F]
  ): BoundedSemilattice[RetryPolicy[F, Res]] =
    new BoundedSemilattice[RetryPolicy[F, Res]]:
      override def empty: RetryPolicy[F, Res] =
        RetryPolicies.constantDelay[F](Duration.Zero)

      override def combine(
          x: RetryPolicy[F, Res],
          y: RetryPolicy[F, Res]
      ): RetryPolicy[F, Res] = x.join(y)

  given [F[_], Res]: Show[RetryPolicy[F, Res]] =
    Show.show(_.show)
end RetryPolicy
