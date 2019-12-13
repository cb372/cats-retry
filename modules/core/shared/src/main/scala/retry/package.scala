import cats.{Id, Monad, MonadError}
import cats.syntax.functor._
import cats.syntax.flatMap._

import scala.concurrent.duration.FiniteDuration

package object retry {
  def retryingM[A] = new RetryingPartiallyApplied[A]

  private[retry] class RetryingPartiallyApplied[A] {
    def apply[M[_]](
        policy: RetryPolicy[M],
        wasSuccessful: A => Boolean,
        onFailure: (A, RetryDetails) => M[Unit]
    )(
        action: => M[A]
    )(
        implicit
        M: Monad[M],
        S: Sleep[M]
    ): M[A] = {
      def performNextStep(failedResult: A, nextStep: NextStep): M[A] =
        nextStep match {
          case NextStep.RetryAfterDelay(delay, updatedStatus) =>
            S.sleep(delay) >> performAction(updatedStatus)
          case NextStep.GiveUp =>
            M.pure(failedResult)
        }

      def handleFailure(failedResult: A, status: RetryStatus): M[A] = {
        for {
          nextStep <- applyPolicy(policy, status)
          _        <- onFailure(failedResult, buildRetryDetails(status, nextStep))
          result   <- performNextStep(failedResult, nextStep)
        } yield result
      }

      def performAction(status: RetryStatus): M[A] =
        for {
          a <- action
          result <- if (wasSuccessful(a)) M.pure(a)
          else handleFailure(a, status)
        } yield result

      performAction(RetryStatus.NoRetriesYet)
    }
  }

  def retryingOnSomeErrors[A] = new RetryingOnSomeErrorsPartiallyApplied[A]

  private[retry] class RetryingOnSomeErrorsPartiallyApplied[A] {
    def apply[M[_], E](
        policy: RetryPolicy[M],
        isWorthRetrying: E => Boolean,
        onError: (E, RetryDetails) => M[Unit]
    )(
        action: => M[A]
    )(
        implicit
        ME: MonadError[M, E],
        S: Sleep[M]
    ): M[A] = {
      def performNextStep(error: E, nextStep: NextStep): M[A] =
        nextStep match {
          case NextStep.RetryAfterDelay(delay, updatedStatus) =>
            S.sleep(delay) >> performAction(updatedStatus)
          case NextStep.GiveUp =>
            ME.raiseError[A](error)
        }

      def handleError(error: E, status: RetryStatus): M[A] = {
        for {
          nextStep <- applyPolicy(policy, status)
          _        <- onError(error, buildRetryDetails(status, nextStep))
          result   <- performNextStep(error, nextStep)
        } yield result
      }

      def performAction(status: RetryStatus): M[A] =
        ME.recoverWith(action) {
          case error if isWorthRetrying(error) => handleError(error, status)
        }

      performAction(RetryStatus.NoRetriesYet)
    }
  }

  def retryingOnAllErrors[A] = new RetryingOnAllErrorsPartiallyApplied[A]

  private[retry] class RetryingOnAllErrorsPartiallyApplied[A] {
    def apply[M[_], E](
        policy: RetryPolicy[M],
        onError: (E, RetryDetails) => M[Unit]
    )(
        action: => M[A]
    )(
        implicit
        ME: MonadError[M, E],
        S: Sleep[M]
    ): M[A] = {
      def performNextStep(error: E, nextStep: NextStep): M[A] =
        nextStep match {
          case NextStep.RetryAfterDelay(delay, updatedStatus) =>
            S.sleep(delay) >> performAction(updatedStatus)
          case NextStep.GiveUp =>
            ME.raiseError[A](error)
        }

      def handleError(error: E, status: RetryStatus): M[A] = {
        for {
          nextStep <- applyPolicy(policy, status)
          _        <- onError(error, buildRetryDetails(status, nextStep))
          result   <- performNextStep(error, nextStep)
        } yield result
      }

      def performAction(status: RetryStatus): M[A] =
        ME.handleErrorWith(action)(error => handleError(error, status))

      performAction(RetryStatus.NoRetriesYet)
    }
  }

  def noop[M[_]: Monad, A]: (A, RetryDetails) => M[Unit] =
    (_, _) => Monad[M].pure(())

  private def applyPolicy[M[_]: Monad](
      policy: RetryPolicy[M],
      retryStatus: RetryStatus
  ): M[NextStep] =
    policy.decideNextRetry(retryStatus).map {
      case PolicyDecision.DelayAndRetry(delay) =>
        NextStep.RetryAfterDelay(delay, retryStatus.addRetry(delay))
      case PolicyDecision.GiveUp =>
        NextStep.GiveUp
    }

  private def buildRetryDetails(
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

  private sealed trait NextStep

  private object NextStep {
    case object GiveUp extends NextStep

    final case class RetryAfterDelay(
        delay: FiniteDuration,
        updatedStatus: RetryStatus
    ) extends NextStep
  }
}
