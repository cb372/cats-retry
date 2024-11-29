import cats.{Applicative, Functor, Monad, MonadError}
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

  object ResultHandler:
    /** Construct a ResultHandler that always chooses to retry the same action, no matter what the failure or
      * error.
      *
      * @param log
      *   A chance to do logging, increment metrics, etc
      */
    def alwaysRetry[F[_]: Functor, Res, A](
        log: (Res, RetryDetails) => F[Unit]
    ): ResultHandler[F, Res, A] =
      (res: Res, retryDetails: RetryDetails) => log(res, retryDetails).as(HandlerDecision.Continue)

    /** Pass this to [[alwaysRetry]] if you don't need to do any logging */
    def noop[M[_]: Applicative, A]: (A, RetryDetails) => M[Unit] =
      (_, _) => Applicative[M].unit

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
    ): M[A] = M.tailRecM((action, RetryStatus.NoRetriesYet)) { (currentAction, status) =>
      currentAction.flatMap { actionResult =>
        retryingOnFailuresImpl(policy, resultHandler, status, currentAction, actionResult)
      }
    }

  private[retry] class RetryingOnErrorsPartiallyApplied[A]:
    def apply[M[_], E](
        policy: RetryPolicy[M],
        errorHandler: ResultHandler[M, E, A]
    )(
        action: => M[A]
    )(using
        ME: MonadError[M, E],
        S: Sleep[M]
    ): M[A] = ME.tailRecM((action, RetryStatus.NoRetriesYet)) { (currentAction, status) =>
      ME.attempt(currentAction).flatMap { attempt =>
        retryingOnSomeErrorsImpl(
          policy,
          errorHandler,
          status,
          currentAction,
          attempt
        )
      }
    }

  private[retry] class RetryingOnFailuresAndErrorsPartiallyApplied[A]:
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

      ME.tailRecM((action, RetryStatus.NoRetriesYet)) { (currentAction, status) =>
        ME.attempt(currentAction).flatMap {
          case Right(actionResult) =>
            retryingOnFailuresImpl(policy, valueHandler, status, currentAction, actionResult)
          case attempt =>
            retryingOnSomeErrorsImpl(
              policy,
              errorHandler,
              status,
              currentAction,
              attempt
            )
        }
      }
  end RetryingOnFailuresAndErrorsPartiallyApplied

  /*
   * Implementation
   */

  private def retryingOnFailuresImpl[M[_], A](
      policy: RetryPolicy[M],
      resultHandler: ResultHandler[M, A, A],
      status: RetryStatus,
      currentAction: M[A],
      actionResult: A
  )(using
      M: Monad[M],
      S: Sleep[M]
  ): M[Either[(M[A], RetryStatus), A]] =

    def applyNextStep(
        nextStep: NextStep,
        nextAction: M[A]
    ): M[Either[(M[A], RetryStatus), A]] =
      nextStep match
        case NextStep.RetryAfterDelay(delay, updatedStatus) =>
          S.sleep(delay) *>
            M.pure(Left(nextAction, updatedStatus)) // continue recursion
        case NextStep.GiveUp =>
          M.pure(Right(actionResult)) // stop the recursion

    def applyHandlerDecision(
        handlerDecision: HandlerDecision[M[A]],
        nextStep: NextStep
    ): M[Either[(M[A], RetryStatus), A]] =
      handlerDecision match
        case HandlerDecision.Done =>
          // Success, stop the recursion and return the action's result
          M.pure(Right(actionResult))
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
      handlerDecision <- resultHandler(actionResult, retryDetails)
      result          <- applyHandlerDecision(handlerDecision, nextStep)
    yield result
  end retryingOnFailuresImpl

  private def retryingOnSomeErrorsImpl[M[_], A, E](
      policy: RetryPolicy[M],
      errorHandler: ResultHandler[M, E, A],
      status: RetryStatus,
      currentAction: M[A],
      attempt: Either[E, A]
  )(using
      ME: MonadError[M, E],
      S: Sleep[M]
  ): M[Either[(M[A], RetryStatus), A]] =

    def applyNextStep(
        error: E,
        nextStep: NextStep,
        nextAction: M[A]
    ): M[Either[(M[A], RetryStatus), A]] =
      nextStep match
        case NextStep.RetryAfterDelay(delay, updatedStatus) =>
          S.sleep(delay) *>
            ME.pure(Left(nextAction, updatedStatus)) // continue recursion
        case NextStep.GiveUp =>
          ME.raiseError[A](error).map(Right(_)) // stop the recursion

    def applyHandlerDecision(
        error: E,
        handlerDecision: HandlerDecision[M[A]],
        nextStep: NextStep
    ): M[Either[(M[A], RetryStatus), A]] =
      handlerDecision match
        case HandlerDecision.Done =>
          // Error is not worth retrying. Stop the recursion and raise the error.
          ME.raiseError[A](error).map(Right(_)) // stop the recursion
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
        ME.pure(Right(success)) // stop the recursion
  end retryingOnSomeErrorsImpl

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
