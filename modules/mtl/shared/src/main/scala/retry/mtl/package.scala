package retry.mtl

import cats.effect.Temporal
import cats.mtl.Handle
import cats.syntax.all.*
import retry.*

/*
 * API
 */

def retryingOnErrors[A] = new RetryingOnErrorsPartiallyApplied[A]

/*
 * Partially applied classes
 */

private[retry] class RetryingOnErrorsPartiallyApplied[A]:

  def apply[F[_], E](
      policy: RetryPolicy[F],
      errorHandler: ResultHandler[F, E, A]
  )(
      action: => F[A]
  )(using
      AH: Handle[F, E],
      T: Temporal[F]
  ): F[A] =
    T.tailRecM((action, RetryStatus.NoRetriesYet)) { (currentAction, status) =>
      AH.attempt(currentAction).flatMap { attempt =>
        retryingOnErrorsImpl(
          policy,
          errorHandler,
          status,
          currentAction,
          attempt
        )
      }
    }

/*
 * Implementation
 */

private def retryingOnErrorsImpl[F[_], A, E](
    policy: RetryPolicy[F],
    errorHandler: ResultHandler[F, E, A],
    status: RetryStatus,
    currentAction: F[A],
    attempt: Either[E, A]
)(using
    AH: Handle[F, E],
    T: Temporal[F]
): F[Either[(F[A], RetryStatus), A]] =

  def applyNextStep(
      error: E,
      nextStep: NextStep,
      nextAction: F[A]
  ): F[Either[(F[A], RetryStatus), A]] =
    nextStep match
      case NextStep.RetryAfterDelay(delay, updatedStatus) =>
        T.sleep(delay) *>
          T.pure(Left(nextAction, updatedStatus)) // continue recursion
      case NextStep.GiveUp =>
        AH.raise[E, A](error).map(Right(_)) // stop the recursion

  def applyHandlerDecision(
      error: E,
      handlerDecision: HandlerDecision[F[A]],
      nextStep: NextStep
  ): F[Either[(F[A], RetryStatus), A]] =
    handlerDecision match
      case HandlerDecision.Stop =>
        // Error is not worth retrying. Stop the recursion and raise the error.
        AH.raise[E, A](error).map(Right(_))
      case HandlerDecision.Continue =>
        // Depending on what the retry policy decided,
        // either delay and then retry the same action, or give up
        applyNextStep(error, nextStep, currentAction)
      case HandlerDecision.Adapt(newAction) =>
        // Depending on what the retry policy decided,
        // either delay and then try a new action, or give up
        applyNextStep(error, nextStep, newAction)

  attempt match
    case Left(error) =>
      for
        nextStep <- applyPolicy(policy, status)
        retryDetails = buildRetryDetails(status, nextStep)
        handlerDecision <- errorHandler(error, retryDetails)
        result          <- applyHandlerDecision(error, handlerDecision, nextStep)
      yield result
    case Right(success) =>
      T.pure(Right(success)) // stop the recursion
end retryingOnErrorsImpl
