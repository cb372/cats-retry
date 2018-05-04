import cats.{Monad, MonadError}
import cats.syntax.functor._
import cats.syntax.flatMap._

package object retry {

  /*
  Make first attempt.
  Decide if it was successful.
  If it was, return successful result.
  If not:
   - Decide whether to retry and what the next delay will be.
   - Log the failed result, and what we are going to do next.
   - If we are going to retry, sleep for the delay and then retry.
   - If not, return the failed result.
   */

  def retrying[A] = new RetryingPartiallyApplied[A]

  class RetryingPartiallyApplied[A] {

    def apply[M[_]](policy: RetryPolicy[M],
                    wasSuccessful: A => Boolean,
                    onFailure: (A, NextStep) => M[Unit])(
        action: => M[A])(implicit M: Monad[M], S: Sleep[M]): M[A] = {

      def performNextStep(failedResult: A, nextStep: NextStep): M[A] =
        nextStep match {
          case WillRetryAfterDelay(delay, _, updatedStatus) =>
            S.sleep(delay).flatMap(_ => performAction(updatedStatus))
          case WillGiveUp(_) =>
            M.pure(failedResult)
        }

      def handleFailure(failedResult: A, status: RetryStatus): M[A] = {
        for {
          nextStep <- applyPolicy(policy, status)
          _        <- onFailure(failedResult, nextStep)
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

  class RetryingOnSomeErrorsPartiallyApplied[A] {

    def apply[M[_], E](policy: RetryPolicy[M],
                       isWorthRetrying: E => Boolean,
                       onError: (E, NextStep) => M[Unit])(
        action: => M[A])(implicit ME: MonadError[M, E], S: Sleep[M]): M[A] = {

      def performNextStep(error: E, nextStep: NextStep): M[A] =
        nextStep match {
          case WillRetryAfterDelay(delay, _, updatedStatus) =>
            S.sleep(delay).flatMap(_ => performAction(updatedStatus))
          case WillGiveUp(_) =>
            ME.raiseError[A](error)
        }

      def handleError(error: E, status: RetryStatus): M[A] = {
        for {
          nextStep <- applyPolicy(policy, status)
          _        <- onError(error, nextStep)
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

  class RetryingOnAllErrorsPartiallyApplied[A] {

    def retryingOnAllErrors[M[_], E](policy: RetryPolicy[M],
                                     onError: (E, NextStep) => M[Unit])(
        action: => M[A])(implicit ME: MonadError[M, E], S: Sleep[M]): M[A] = {

      def performNextStep(error: E, nextStep: NextStep): M[A] =
        nextStep match {
          case WillRetryAfterDelay(delay, _, updatedStatus) =>
            S.sleep(delay).flatMap(_ => performAction(updatedStatus))
          case WillGiveUp(_) =>
            ME.raiseError[A](error)
        }

      def handleError(error: E, status: RetryStatus): M[A] = {
        for {
          nextStep <- applyPolicy(policy, status)
          _        <- onError(error, nextStep)
          result   <- performNextStep(error, nextStep)
        } yield result
      }

      def performAction(status: RetryStatus): M[A] =
        ME.handleErrorWith(action)(error => handleError(error, status))

      performAction(RetryStatus.NoRetriesYet)
    }

  }

  def noop[M[_]: Monad, A]: (A, NextStep) => M[Unit] =
    (_, _) => Monad[M].pure(())

  private def applyPolicy[M[_]: Monad](policy: RetryPolicy[M],
                                       retryStatus: RetryStatus): M[NextStep] =
    policy.decideNextRetry(retryStatus).map {
      case DelayAndRetry(delay) =>
        WillRetryAfterDelay(delay, retryStatus, retryStatus.addRetry(delay))
      case GiveUp => WillGiveUp(retryStatus)
    }

}
