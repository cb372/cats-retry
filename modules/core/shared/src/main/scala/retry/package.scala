import cats.{Applicative, Functor}
import cats.effect.Temporal
import cats.syntax.apply.*
import cats.syntax.functor.*
import cats.syntax.flatMap.*

import scala.concurrent.duration.FiniteDuration

package object retry:

  /*
   * API
   */

  def retryingOnFailures[A] = new RetryingOnFailuresPartiallyApplied[A]

  def retryingOnErrors[A] = new RetryingOnErrorsPartiallyApplied[A]

  def retryingOnFailuresAndErrors[A] =
    new RetryingOnFailuresAndErrorsPartiallyApplied[A]

  /** A handler that inspects the result of an action and decides what to do next. This is also a good place
    * to do any logging.
    */
  type ResultHandler[F[_], -Res, A] = (Res, RetryDetails) => F[HandlerDecision[F[A]]]

  // Type aliases for different flavours of handler
  type ValueHandler[F[_], A]        = ResultHandler[F, A, A]
  type ErrorHandler[F[_], A]        = ResultHandler[F, Throwable, A]
  type ErrorOrValueHandler[F[_], A] = ResultHandler[F, Either[Throwable, A], A]

  object ResultHandler:
    /** Construct a ResultHandler that always chooses to retry the same action, no matter what the error.
      *
      * @param log
      *   A chance to do logging, increment metrics, etc
      */
    def retryOnAllErrors[F[_]: Functor, Res, A](
        log: (Res, RetryDetails) => F[Unit]
    ): ResultHandler[F, Res, A] =
      (res: Res, retryDetails: RetryDetails) => log(res, retryDetails).as(HandlerDecision.Continue)

    /** Pass this to [[retryOnAllErrors]] if you don't need to do any logging */
    def noop[F[_]: Applicative, A]: (A, RetryDetails) => F[Unit] =
      (_, _) => Applicative[F].unit

  /*
   * Partially applied classes
   */

  private[retry] class RetryingOnFailuresPartiallyApplied[A]:
    def apply[F[_]](
        policy: RetryPolicy[F],
        valueHandler: ValueHandler[F, A]
    )(
        action: => F[A]
    )(using
        T: Temporal[F]
    ): F[A] = T.tailRecM((action, RetryStatus.NoRetriesYet)) { (currentAction, status) =>
      currentAction.flatMap { actionResult =>
        retryingOnFailuresImpl(policy, valueHandler, status, currentAction, actionResult)
      }
    }

  private[retry] class RetryingOnErrorsPartiallyApplied[A]:
    def apply[F[_]](
        policy: RetryPolicy[F],
        errorHandler: ErrorHandler[F, A]
    )(
        action: => F[A]
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

  private[retry] class RetryingOnFailuresAndErrorsPartiallyApplied[A]:
    def apply[F[_]](
        policy: RetryPolicy[F],
        errorOrValueHandler: ErrorOrValueHandler[F, A]
    )(
        action: => F[A]
    )(using
        T: Temporal[F]
    ): F[A] =
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
            )
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
  ): F[Either[(F[A], RetryStatus), A]] =

    def applyNextStep(
        nextStep: NextStep,
        nextAction: F[A]
    ): F[Either[(F[A], RetryStatus), A]] =
      nextStep match
        case NextStep.RetryAfterDelay(delay, updatedStatus) =>
          T.sleep(delay) *>
            T.pure(Left(nextAction, updatedStatus)) // continue recursion
        case NextStep.GiveUp =>
          T.pure(Right(actionResult)) // stop the recursion

    def applyHandlerDecision(
        handlerDecision: HandlerDecision[F[A]],
        nextStep: NextStep
    ): F[Either[(F[A], RetryStatus), A]] =
      handlerDecision match
        case HandlerDecision.Stop =>
          // Success, stop the recursion and return the action's result
          T.pure(Right(actionResult))
        case HandlerDecision.Continue =>
          // Depending on what the retry policy decided,
          // either delay and then retry the same action, or give up
          applyNextStep(nextStep, currentAction)
        case HandlerDecision.Adapt(newAction) =>
          // Depending on what the retry policy decided,
          // either delay and then try a new action, or give up
          applyNextStep(nextStep, newAction)

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
end retry
