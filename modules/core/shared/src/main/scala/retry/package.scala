import cats.{Monad, MonadError}
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

  def noop[M[_]: Monad, A]: (A, RetryDetails) => M[Unit] =
    (_, _) => Monad[M].pure(())

  /**
    * A handler that inspects the result of an action and decides
    * what to do next.
    * This is also a good place to do any logging.
    */
  type ResultHandler[F[_], Res, A] = (Res, RetryDetails) => F[HandlerDecision[F, A]]

  /*
   * Partially applied classes
   */

  private[retry] class RetryingOnFailuresPartiallyApplied[A]:
    def apply[M[_]](
        policy: RetryPolicy[M],
        resultHandler: ResultHandler[M, A, A]
    )(
        action: => M[A]
    )(using
        M: Monad[M],
        S: Sleep[M]
    ): M[A] = M.tailRecM(RetryStatus.NoRetriesYet) { status =>
      action.flatMap { a =>
        retryingOnFailuresImpl(policy, resultHandler, status, a)
      }
    }

  private[retry] class RetryingOnSomeErrorsPartiallyApplied[A]:
    def apply[M[_], E](
        policy: RetryPolicy[M],
        errorHandler: ResultHandler[M, E, A]
    )(
        action: => M[A]
    )(using
        ME: MonadError[M, E],
        S: Sleep[M]
    ): M[A] = ME.tailRecM(RetryStatus.NoRetriesYet) { status =>
      ME.attempt(action).flatMap { attempt =>
        retryingOnSomeErrorsImpl(
          policy,
          errorHandler,
          status,
          attempt
        )
      }
    }

  // TODO could we get rid of this variant to reduce the surface area of the API?
  // It feels a bit redundant.
  // retryingOnSomeErrors could be renamed to simply retryingOnErrors,
  // since the errorHandler can choose whether to retry on all errors or only some.
  private[retry] class RetryingOnAllErrorsPartiallyApplied[A]:
    def apply[M[_], E](
        policy: RetryPolicy[M],
        onError: (E, RetryDetails) => M[Unit]
    )(
        action: => M[A]
    )(using
        ME: MonadError[M, E],
        S: Sleep[M]
    ): M[A] =
      val errorHandler: ResultHandler[M, E, A] =
        (e: E, rd: RetryDetails) => onError(e, rd).as(HandlerDecision.Continue)
      retryingOnSomeErrors[A].apply[M, E](policy, errorHandler)(
        action
      )

  private[retry] class RetryingOnFailuresAndSomeErrorsPartiallyApplied[A]:
    def apply[M[_], E](
        policy: RetryPolicy[M],
        resultOrErrorHandler: ResultHandler[M, Either[E, A], A]
    )(
        action: => M[A]
    )(using
        ME: MonadError[M, E],
        S: Sleep[M]
    ): M[A] =
      val valueHandler: ResultHandler[M, A, A] =
        (a: A, rd: RetryDetails) => resultOrErrorHandler(Right(a), rd)
      val errorHandler: ResultHandler[M, E, A] =
        (e: E, rd: RetryDetails) => resultOrErrorHandler(Left(e), rd)

      ME.tailRecM(RetryStatus.NoRetriesYet) { status =>
        ME.attempt(action).flatMap {
          case Right(a) =>
            retryingOnFailuresImpl(policy, valueHandler, status, a)
          case attempt =>
            retryingOnSomeErrorsImpl(
              policy,
              errorHandler,
              status,
              attempt
            )
        }
      }

  private[retry] class RetryingOnFailuresAndAllErrorsPartiallyApplied[A]:
    def apply[M[_], E](
        policy: RetryPolicy[M],
        resultHandler: ResultHandler[M, A, A],
        onError: (E, RetryDetails) => M[Unit]
    )(
        action: => M[A]
    )(using
        ME: MonadError[M, E],
        S: Sleep[M]
    ): M[A] =
      val resultOrErrorHandler: ResultHandler[M, Either[E, A], A] =
        (result: Either[E, A], rd: RetryDetails) => result match {
          case Left(e) => onError(e, rd).as(HandlerDecision.Continue)
          case Right(a) => resultHandler(a, rd)
      }

      retryingOnFailuresAndSomeErrors[A]
        .apply[M, E](
          policy,
          resultOrErrorHandler
        )(
          action
        )

  /*
   * Implementation
   */

  private def retryingOnFailuresImpl[M[_], A](
      policy: RetryPolicy[M],
      wasSuccessful: A => M[Boolean],
      onFailure: (A, RetryDetails) => M[Unit],
      status: RetryStatus,
      a: A
  )(using
      M: Monad[M],
      S: Sleep[M]
  ): M[Either[RetryStatus, A]] =

    def onFalse: M[Either[RetryStatus, A]] = for
      nextStep <- applyPolicy(policy, status)
      _        <- onFailure(a, buildRetryDetails(status, nextStep))
      result <- nextStep match
        case NextStep.RetryAfterDelay(delay, updatedStatus) =>
          S.sleep(delay) *>
            M.pure(Left(updatedStatus)) // continue recursion
        case NextStep.GiveUp =>
          M.pure(Right(a)) // stop the recursion
    yield result

    wasSuccessful(a).ifM(
      M.pure(Right(a)), // stop the recursion
      onFalse
    )

  private def retryingOnSomeErrorsImpl[M[_], A, E](
      policy: RetryPolicy[M],
      isWorthRetrying: E => M[Boolean],
      onError: (E, RetryDetails) => M[Unit],
      status: RetryStatus,
      attempt: Either[E, A]
  )(using
      ME: MonadError[M, E],
      S: Sleep[M]
  ): M[Either[RetryStatus, A]] = attempt match
    case Left(error) =>
      isWorthRetrying(error).ifM(
        for
          nextStep <- applyPolicy(policy, status)
          _        <- onError(error, buildRetryDetails(status, nextStep))
          result <- nextStep match
            case NextStep.RetryAfterDelay(delay, updatedStatus) =>
              S.sleep(delay) *>
                ME.pure(Left(updatedStatus)) // continue recursion
            case NextStep.GiveUp =>
              ME.raiseError[A](error).map(Right(_)) // stop the recursion
        yield result,
        ME.raiseError[A](error).map(Right(_)) // stop the recursion
      )
    case Right(success) =>
      ME.pure(Right(success)) // stop the recursion

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
