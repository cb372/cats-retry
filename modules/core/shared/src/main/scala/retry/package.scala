import cats.{Monad, MonadError}
import cats.syntax.apply._
import cats.syntax.functor._
import cats.syntax.flatMap._

import scala.concurrent.duration.FiniteDuration

package object retry {
  @deprecated("Use retryingOnFailures instead", "2.1.0")
  def retryingM[A] = new RetryingOnFailuresPartiallyApplied[A]
  def retryingOnFailures[A] = new RetryingOnFailuresPartiallyApplied[A]

  private def retryingOnFailuresImpl[M[_], A](
      policy: RetryPolicy[M],
      wasSuccessful: A => M[Boolean],
      onFailure: (A, RetryDetails) => M[Unit],
      status: RetryStatus,
      a: A
  )(implicit
      M: Monad[M],
      S: Sleep[M]
  ): M[Either[RetryStatus, A]] = {

    def onFalse: M[Either[RetryStatus, A]] = for {
      nextStep <- applyPolicy(policy, status)
      _        <- onFailure(a, buildRetryDetails(status, nextStep))
      result <- nextStep match {
        case NextStep.RetryAfterDelay(delay, updatedStatus) =>
          S.sleep(delay) *>
            M.pure(Left(updatedStatus)) // continue recursion
        case NextStep.GiveUp =>
          M.pure(Right(a)) // stop the recursion
      }
    } yield result

    wasSuccessful(a).ifM(
      M.pure(Right(a)), // stop the recursion
      onFalse
    )
  }

  private[retry] class RetryingOnFailuresPartiallyApplied[A] {
    def apply[M[_]](
        policy: RetryPolicy[M],
        wasSuccessful: A => M[Boolean],
        onFailure: (A, RetryDetails) => M[Unit]
    )(
        action: => M[A]
    )(implicit
        M: Monad[M],
        S: Sleep[M]
    ): M[A] = M.tailRecM(RetryStatus.NoRetriesYet) { status =>
      action.flatMap { a =>
        retryingOnFailuresImpl(policy, wasSuccessful, onFailure, status, a)
      }
    }
  }

  def retryingOnSomeErrors[A] = new RetryingOnSomeErrorsPartiallyApplied[A]

  private def retryingOnSomeErrorsImpl[M[_], A, E](
      policy: RetryPolicy[M],
      isWorthRetrying: E => M[Boolean],
      onError: (E, RetryDetails) => M[Unit],
      status: RetryStatus,
      attempt: Either[E, A]
  )(implicit
      ME: MonadError[M, E],
      S: Sleep[M]
  ): M[Either[RetryStatus, A]] = attempt match {
    case Left(error) =>
      isWorthRetrying(error).ifM(
        for {
          nextStep <- applyPolicy(policy, status)
          _        <- onError(error, buildRetryDetails(status, nextStep))
          result <- nextStep match {
            case NextStep.RetryAfterDelay(delay, updatedStatus) =>
              S.sleep(delay) *>
                ME.pure(Left(updatedStatus)) // continue recursion
            case NextStep.GiveUp =>
              ME.raiseError[A](error).map(Right(_)) // stop the recursion
          }
        } yield result,
        ME.raiseError[A](error).map(Right(_)) // stop the recursion
      )
    case Right(success) =>
      ME.pure(Right(success)) // stop the recursion
  }

  private[retry] class RetryingOnSomeErrorsPartiallyApplied[A] {
    def apply[M[_], E](
        policy: RetryPolicy[M],
        isWorthRetrying: E => M[Boolean],
        onError: (E, RetryDetails) => M[Unit]
    )(
        action: => M[A]
    )(implicit
        ME: MonadError[M, E],
        S: Sleep[M]
    ): M[A] = ME.tailRecM(RetryStatus.NoRetriesYet) { status =>
      ME.attempt(action).flatMap { attempt =>
        retryingOnSomeErrorsImpl(
          policy,
          isWorthRetrying,
          onError,
          status,
          attempt
        )
      }
    }
  }

  def retryingOnAllErrors[A] = new RetryingOnAllErrorsPartiallyApplied[A]

  private[retry] class RetryingOnAllErrorsPartiallyApplied[A] {
    def apply[M[_], E](
        policy: RetryPolicy[M],
        onError: (E, RetryDetails) => M[Unit]
    )(
        action: => M[A]
    )(implicit
        ME: MonadError[M, E],
        S: Sleep[M]
    ): M[A] =
      retryingOnSomeErrors[A].apply[M, E](policy, _ => ME.pure(true), onError)(
        action
      )
  }

  def retryingOnFailuresAndSomeErrors[A] =
    new RetryingOnFailuresAndSomeErrorsPartiallyApplied[A]

  private[retry] class RetryingOnFailuresAndSomeErrorsPartiallyApplied[A] {
    def apply[M[_], E](
        policy: RetryPolicy[M],
        wasSuccessful: A => M[Boolean],
        isWorthRetrying: E => M[Boolean],
        onFailure: (A, RetryDetails) => M[Unit],
        onError: (E, RetryDetails) => M[Unit]
    )(
        action: => M[A]
    )(implicit
        ME: MonadError[M, E],
        S: Sleep[M]
    ): M[A] = {

      ME.tailRecM(RetryStatus.NoRetriesYet) { status =>
        ME.attempt(action).flatMap {
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
    }
  }

  def retryingOnFailuresAndAllErrors[A] =
    new RetryingOnFailuresAndAllErrorsPartiallyApplied[A]

  private[retry] class RetryingOnFailuresAndAllErrorsPartiallyApplied[A] {
    def apply[M[_], E](
        policy: RetryPolicy[M],
        wasSuccessful: A => M[Boolean],
        onFailure: (A, RetryDetails) => M[Unit],
        onError: (E, RetryDetails) => M[Unit]
    )(
        action: => M[A]
    )(implicit
        ME: MonadError[M, E],
        S: Sleep[M]
    ): M[A] =
      retryingOnFailuresAndSomeErrors[A]
        .apply[M, E](
          policy,
          wasSuccessful,
          _ => ME.pure(true),
          onFailure,
          onError
        )(
          action
        )
  }

  def noop[M[_]: Monad, A]: (A, RetryDetails) => M[Unit] =
    (_, _) => Monad[M].pure(())

  private[retry] def applyPolicy[M[_]: Monad](
      policy: RetryPolicy[M],
      retryStatus: RetryStatus
  ): M[NextStep] =
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
    nextStep match {
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
    }

  private[retry] sealed trait NextStep

  private[retry] object NextStep {
    case object GiveUp extends NextStep

    final case class RetryAfterDelay(
        delay: FiniteDuration,
        updatedStatus: RetryStatus
    ) extends NextStep
  }
}
