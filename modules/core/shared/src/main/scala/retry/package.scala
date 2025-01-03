package retry

import cats.Functor
import cats.effect.Temporal
import cats.syntax.apply.*
import cats.syntax.functor.*
import cats.syntax.flatMap.*

import scala.concurrent.duration.FiniteDuration

/*
 * API
 */

def retryingOnFailures[F[_], A](
    action: F[A]
)(
    policy: RetryPolicy[F],
    valueHandler: ValueHandler[F, A]
)(using
    T: Temporal[F]
): F[Either[A, A]] = T.tailRecM((action, RetryStatus.NoRetriesYet)) { (currentAction, status) =>
  currentAction.flatMap { actionResult =>
    retryingOnFailuresImpl(policy, valueHandler, status, currentAction, actionResult)
  }
}

def retryingOnErrors[F[_], A](
    action: F[A]
)(
    policy: RetryPolicy[F],
    errorHandler: ErrorHandler[F, A]
)(using
    T: Temporal[F]
): F[A] = T.tailRecM((action, RetryStatus.NoRetriesYet)) { (currentAction, status) =>
  T.attempt(currentAction).flatMap { attempt =>
    retryingOnErrorsImpl(
      policy,
      errorHandler,
      status,
      currentAction,
      attempt
    )
  }
}

def retryingOnFailuresAndErrors[F[_], A](
    action: F[A]
)(
    policy: RetryPolicy[F],
    errorOrValueHandler: ErrorOrValueHandler[F, A]
)(using
    T: Temporal[F]
): F[Either[A, A]] =
  val valueHandler: ResultHandler[F, A, A] =
    (a: A, rd: RetryDetails) => errorOrValueHandler(Right(a), rd)
  val errorHandler: ResultHandler[F, Throwable, A] =
    (e: Throwable, rd: RetryDetails) => errorOrValueHandler(Left(e), rd)

  T.tailRecM((action, RetryStatus.NoRetriesYet)) { (currentAction, status) =>
    T.attempt(currentAction).flatMap {
      case Right(actionResult) =>
        retryingOnFailuresImpl(policy, valueHandler, status, currentAction, actionResult)
      case attempt =>
        retryingOnErrorsImpl(
          policy,
          errorHandler,
          status,
          currentAction,
          attempt
        ).map(_.map(Right(_)))
    }
  }

/*
 * Implementation
 */

private def retryingOnFailuresImpl[F[_], A](
    policy: RetryPolicy[F],
    valueHandler: ValueHandler[F, A],
    status: RetryStatus,
    currentAction: F[A],
    actionResult: A
)(using
    T: Temporal[F]
): F[Either[(F[A], RetryStatus), Either[A, A]]] =

  def applyNextStep(
      nextStep: NextStep,
      nextAction: F[A],
      valueToReturn: Either[A, A]
  ): F[Either[(F[A], RetryStatus), Either[A, A]]] =
    nextStep match
      case NextStep.RetryAfterDelay(delay, updatedStatus) =>
        T.sleep(delay) *>
          T.pure(Left(nextAction, updatedStatus)) // continue recursion
      case NextStep.GiveUp =>
        T.pure(Right(valueToReturn)) // stop the recursion

  def applyHandlerDecision(
      handlerDecision: HandlerDecision[F[A]],
      nextStep: NextStep
  ): F[Either[(F[A], RetryStatus), Either[A, A]]] =
    handlerDecision match
      case HandlerDecision.Stop =>
        // Success. Stop the recursion and return the action's result.
        T.pure(Right(Right(actionResult)))
      case HandlerDecision.Continue =>
        // Failure. Depending on what the retry policy decided,
        // either delay and then retry the same action, or give up.
        applyNextStep(nextStep, currentAction, Left(actionResult))
      case HandlerDecision.Adapt(newAction) =>
        // Failure. Depending on what the retry policy decided,
        // either delay and then try a new action, or give up.
        applyNextStep(nextStep, newAction, Left(actionResult))

  for
    nextStep <- applyPolicy(policy, status)
    retryDetails = buildRetryDetails(status, nextStep)
    handlerDecision <- valueHandler(actionResult, retryDetails)
    result          <- applyHandlerDecision(handlerDecision, nextStep)
  yield result
end retryingOnFailuresImpl

private def retryingOnErrorsImpl[F[_], A](
    policy: RetryPolicy[F],
    errorHandler: ErrorHandler[F, A],
    status: RetryStatus,
    currentAction: F[A],
    attempt: Either[Throwable, A]
)(using
    T: Temporal[F]
): F[Either[(F[A], RetryStatus), A]] =

  def applyNextStep(
      error: Throwable,
      nextStep: NextStep,
      nextAction: F[A]
  ): F[Either[(F[A], RetryStatus), A]] =
    nextStep match
      case NextStep.RetryAfterDelay(delay, updatedStatus) =>
        T.sleep(delay) *>
          T.pure(Left(nextAction, updatedStatus)) // continue recursion
      case NextStep.GiveUp =>
        T.raiseError[A](error).map(Right(_)) // stop the recursion

  def applyHandlerDecision(
      error: Throwable,
      handlerDecision: HandlerDecision[F[A]],
      nextStep: NextStep
  ): F[Either[(F[A], RetryStatus), A]] =
    handlerDecision match
      case HandlerDecision.Stop =>
        // Error is not worth retrying. Stop the recursion and raise the error.
        T.raiseError[A](error).map(Right(_))
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

private[retry] def applyPolicy[F[_]: Functor](
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
      RetryDetails(
        currentStatus.retriesSoFar,
        currentStatus.cumulativeDelay,
        RetryDetails.NextStep.DelayAndRetry(delay)
      )
    case NextStep.GiveUp =>
      RetryDetails(
        currentStatus.retriesSoFar,
        currentStatus.cumulativeDelay,
        RetryDetails.NextStep.GiveUp
      )

private[retry] sealed trait NextStep

private[retry] object NextStep:
  case object GiveUp extends NextStep

  final case class RetryAfterDelay(
      delay: FiniteDuration,
      updatedStatus: RetryStatus
  ) extends NextStep
