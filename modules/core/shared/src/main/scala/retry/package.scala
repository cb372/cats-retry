import cats.{Monad}
import cats.effect.Temporal
import cats.syntax.apply.*
import cats.syntax.functor.*
import cats.syntax.flatMap.*

import scala.concurrent.duration.FiniteDuration

package object retry:

  /*
   * API
   */

  @deprecated("Use retryingOnFailures instead", "2.1.0")
  def retryingM[A]          = new RetryingOnFailuresPartiallyApplied[A]
  def retryingOnFailures[A] = new RetryingOnFailuresPartiallyApplied[A]

  def retryingOnSomeErrors[A] = new RetryingOnSomeErrorsPartiallyApplied[A]

  def retryingOnAllErrors[A] = new RetryingOnAllErrorsPartiallyApplied[A]

  def retryingOnFailuresAndSomeErrors[A] =
    new RetryingOnFailuresAndSomeErrorsPartiallyApplied[A]

  def retryingOnFailuresAndAllErrors[A] =
    new RetryingOnFailuresAndAllErrorsPartiallyApplied[A]

  def noop[F[_]: Monad, A]: (A, RetryDetails) => F[Unit] =
    (_, _) => Monad[F].pure(())

  /*
   * Partially applied classes
   */

  private[retry] class RetryingOnFailuresPartiallyApplied[A]:
    def apply[F[_]](
        policy: RetryPolicy[F],
        wasSuccessful: A => F[Boolean],
        onFailure: (A, RetryDetails) => F[Unit]
    )(
        action: => F[A]
    )(using
        T: Temporal[F]
    ): F[A] = T.tailRecM(RetryStatus.NoRetriesYet) { status =>
      action.flatMap { a =>
        retryingOnFailuresImpl(policy, wasSuccessful, onFailure, status, a)
      }
    }

  private[retry] class RetryingOnSomeErrorsPartiallyApplied[A]:
    def apply[F[_]](
        policy: RetryPolicy[F],
        isWorthRetrying: Throwable => F[Boolean],
        onError: (Throwable, RetryDetails) => F[Unit]
    )(
        action: => F[A]
    )(using
        T: Temporal[F]
    ): F[A] = T.tailRecM(RetryStatus.NoRetriesYet) { status =>
      T.attempt(action).flatMap { attempt =>
        retryingOnSomeErrorsImpl(
          policy,
          isWorthRetrying,
          onError,
          status,
          attempt
        )
      }
    }

  private[retry] class RetryingOnAllErrorsPartiallyApplied[A]:
    def apply[F[_]](
        policy: RetryPolicy[F],
        onError: (Throwable, RetryDetails) => F[Unit]
    )(
        action: => F[A]
    )(using
        T: Temporal[F]
    ): F[A] =
      retryingOnSomeErrors[A].apply[F](policy, _ => T.pure(true), onError)(
        action
      )

  private[retry] class RetryingOnFailuresAndSomeErrorsPartiallyApplied[A]:
    def apply[F[_]](
        policy: RetryPolicy[F],
        wasSuccessful: A => F[Boolean],
        isWorthRetrying: Throwable => F[Boolean],
        onFailure: (A, RetryDetails) => F[Unit],
        onError: (Throwable, RetryDetails) => F[Unit]
    )(
        action: => F[A]
    )(using
        T: Temporal[F]
    ): F[A] =
      T.tailRecM(RetryStatus.NoRetriesYet) { status =>
        T.attempt(action).flatMap {
          case Right(a) =>
            retryingOnFailuresImpl(policy, wasSuccessful, onFailure, status, a)
          case attempt =>
            retryingOnSomeErrorsImpl(
              policy,
              isWorthRetrying,
              onError,
              status,
              attempt
            )
        }
      }

  private[retry] class RetryingOnFailuresAndAllErrorsPartiallyApplied[A]:
    def apply[F[_]](
        policy: RetryPolicy[F],
        wasSuccessful: A => F[Boolean],
        onFailure: (A, RetryDetails) => F[Unit],
        onError: (Throwable, RetryDetails) => F[Unit]
    )(
        action: => F[A]
    )(using
        T: Temporal[F]
    ): F[A] =
      retryingOnFailuresAndSomeErrors[A]
        .apply[F](
          policy,
          wasSuccessful,
          _ => T.pure(true),
          onFailure,
          onError
        )(
          action
        )

  /*
   * Implementation
   */

  private def retryingOnFailuresImpl[F[_], A](
      policy: RetryPolicy[F],
      wasSuccessful: A => F[Boolean],
      onFailure: (A, RetryDetails) => F[Unit],
      status: RetryStatus,
      a: A
  )(using
      T: Temporal[F]
  ): F[Either[RetryStatus, A]] =
    def onFalse: F[Either[RetryStatus, A]] = for
      nextStep <- applyPolicy(policy, status)
      _        <- onFailure(a, buildRetryDetails(status, nextStep))
      result <- nextStep match
        case NextStep.RetryAfterDelay(delay, updatedStatus) =>
          T.sleep(delay) *>
            T.pure(Left(updatedStatus)) // continue recursion
        case NextStep.GiveUp =>
          T.pure(Right(a)) // stop the recursion
    yield result

    wasSuccessful(a).ifM(
      T.pure(Right(a)), // stop the recursion
      onFalse
    )

  private def retryingOnSomeErrorsImpl[F[_], A](
      policy: RetryPolicy[F],
      isWorthRetrying: Throwable => F[Boolean],
      onError: (Throwable, RetryDetails) => F[Unit],
      status: RetryStatus,
      attempt: Either[Throwable, A]
  )(using
      T: Temporal[F]
  ): F[Either[RetryStatus, A]] = attempt match
    case Left(error) =>
      isWorthRetrying(error).ifM(
        for
          nextStep <- applyPolicy(policy, status)
          _        <- onError(error, buildRetryDetails(status, nextStep))
          result <- nextStep match
            case NextStep.RetryAfterDelay(delay, updatedStatus) =>
              T.sleep(delay) *>
                T.pure(Left(updatedStatus)) // continue recursion
            case NextStep.GiveUp =>
              T.raiseError[A](error).map(Right(_)) // stop the recursion
        yield result,
        T.raiseError[A](error).map(Right(_)) // stop the recursion
      )
    case Right(success) =>
      T.pure(Right(success)) // stop the recursion

  private[retry] def applyPolicy[F[_]: Monad](
      policy: RetryPolicy[F],
      retryStatus: RetryStatus
  ): F[NextStep] =
    policy.decideNextRetry(retryStatus).map {
      case PolicyDecision.DelayAndRetry(delay) =>
        NextStep.RetryAfterDelay(delay, retryStatus.addRetry(delay))
      case PolicyDecision.GiveUp =>
        NextStep.GiveUp
    }

  private[retry] def buildRetryDetails(
      currentStatus: RetryStatus,
      nextStep: NextStep
  ): RetryDetails =
    nextStep match
      case NextStep.RetryAfterDelay(delay, _) =>
        RetryDetails.WillDelayAndRetry(
          delay,
          currentStatus.retriesSoFar,
          currentStatus.cumulativeDelay
        )
      case NextStep.GiveUp =>
        RetryDetails.GivingUp(
          currentStatus.retriesSoFar,
          currentStatus.cumulativeDelay
        )

  private[retry] sealed trait NextStep

  private[retry] object NextStep:
    case object GiveUp extends NextStep

    final case class RetryAfterDelay(
        delay: FiniteDuration,
        updatedStatus: RetryStatus
    ) extends NextStep
end retry
