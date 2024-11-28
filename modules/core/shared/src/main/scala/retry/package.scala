import cats.{Monad}
import cats.effect.Temporal
import cats.syntax.apply._
import cats.syntax.functor._
import cats.syntax.flatMap._

import scala.concurrent.duration.FiniteDuration

package object retry {

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

  def noop[M[_]: Monad, A]: (A, RetryDetails) => M[Unit] =
    (_, _) => Monad[M].pure(())

  /*
   * Partially applied classes
   */

  private[retry] class RetryingOnFailuresPartiallyApplied[A] {
    def apply[M[_]](
        policy: RetryPolicy[M],
        wasSuccessful: A => M[Boolean],
        onFailure: (A, RetryDetails) => M[Unit]
    )(
        action: => M[A]
    )(implicit
        T: Temporal[M]
    ): M[A] = T.tailRecM(RetryStatus.NoRetriesYet) { status =>
      action.flatMap { a =>
        retryingOnFailuresImpl(policy, wasSuccessful, onFailure, status, a)
      }
    }
  }

  private[retry] class RetryingOnSomeErrorsPartiallyApplied[A] {
    def apply[M[_]](
        policy: RetryPolicy[M],
        isWorthRetrying: Throwable => M[Boolean],
        onError: (Throwable, RetryDetails) => M[Unit]
    )(
        action: => M[A]
    )(implicit
        T: Temporal[M]
    ): M[A] = T.tailRecM(RetryStatus.NoRetriesYet) { status =>
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
  }

  private[retry] class RetryingOnAllErrorsPartiallyApplied[A] {
    def apply[M[_]](
        policy: RetryPolicy[M],
        onError: (Throwable, RetryDetails) => M[Unit]
    )(
        action: => M[A]
    )(implicit
        T: Temporal[M]
    ): M[A] =
      retryingOnSomeErrors[A].apply[M](policy, _ => T.pure(true), onError)(
        action
      )
  }

  private[retry] class RetryingOnFailuresAndSomeErrorsPartiallyApplied[A] {
    def apply[M[_]](
        policy: RetryPolicy[M],
        wasSuccessful: A => M[Boolean],
        isWorthRetrying: Throwable => M[Boolean],
        onFailure: (A, RetryDetails) => M[Unit],
        onError: (Throwable, RetryDetails) => M[Unit]
    )(
        action: => M[A]
    )(implicit
        T: Temporal[M]
    ): M[A] = {

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
    }
  }

  private[retry] class RetryingOnFailuresAndAllErrorsPartiallyApplied[A] {
    def apply[M[_]](
        policy: RetryPolicy[M],
        wasSuccessful: A => M[Boolean],
        onFailure: (A, RetryDetails) => M[Unit],
        onError: (Throwable, RetryDetails) => M[Unit]
    )(
        action: => M[A]
    )(implicit
        T: Temporal[M]
    ): M[A] =
      retryingOnFailuresAndSomeErrors[A]
        .apply[M](
          policy,
          wasSuccessful,
          _ => T.pure(true),
          onFailure,
          onError
        )(
          action
        )
  }

  /*
   * Implementation
   */

  private def retryingOnFailuresImpl[M[_], A](
      policy: RetryPolicy[M],
      wasSuccessful: A => M[Boolean],
      onFailure: (A, RetryDetails) => M[Unit],
      status: RetryStatus,
      a: A
  )(implicit
      T: Temporal[M]
  ): M[Either[RetryStatus, A]] = {

    def onFalse: M[Either[RetryStatus, A]] = for {
      nextStep <- applyPolicy(policy, status)
      _        <- onFailure(a, buildRetryDetails(status, nextStep))
      result <- nextStep match {
        case NextStep.RetryAfterDelay(delay, updatedStatus) =>
          T.sleep(delay) *>
            T.pure(Left(updatedStatus)) // continue recursion
        case NextStep.GiveUp =>
          T.pure(Right(a)) // stop the recursion
      }
    } yield result

    wasSuccessful(a).ifM(
      T.pure(Right(a)), // stop the recursion
      onFalse
    )
  }

  private def retryingOnSomeErrorsImpl[M[_], A, E <: Throwable](
      policy: RetryPolicy[M],
      isWorthRetrying: E => M[Boolean],
      onError: (E, RetryDetails) => M[Unit],
      status: RetryStatus,
      attempt: Either[E, A]
  )(implicit
      T: Temporal[M]
  ): M[Either[RetryStatus, A]] = attempt match {
    case Left(error) =>
      isWorthRetrying(error).ifM(
        for {
          nextStep <- applyPolicy(policy, status)
          _        <- onError(error, buildRetryDetails(status, nextStep))
          result <- nextStep match {
            case NextStep.RetryAfterDelay(delay, updatedStatus) =>
              T.sleep(delay) *>
                T.pure(Left(updatedStatus)) // continue recursion
            case NextStep.GiveUp =>
              T.raiseError[A](error).map(Right(_)) // stop the recursion
          }
        } yield result,
        T.raiseError[A](error).map(Right(_)) // stop the recursion
      )
    case Right(success) =>
      T.pure(Right(success)) // stop the recursion
  }

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
