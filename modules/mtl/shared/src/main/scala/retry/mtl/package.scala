package retry

import cats.Monad
import cats.mtl.ApplicativeHandle
import cats.syntax.flatMap._
import cats.syntax.functor._

package object mtl {

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
        M: Monad[M],
        AH: ApplicativeHandle[M, E],
        S: Sleep[M]
    ): M[A] = {
      def performNextStep(error: E, nextStep: NextStep): M[A] =
        nextStep match {
          case NextStep.RetryAfterDelay(delay, updatedStatus) =>
            S.sleep(delay) >> performAction(updatedStatus)
          case NextStep.GiveUp =>
            AH.raise(error)
        }

      def handleError(error: E, status: RetryStatus): M[A] = {
        for {
          nextStep <- retry.applyPolicy(policy, status)
          _        <- onError(error, retry.buildRetryDetails(status, nextStep))
          result   <- performNextStep(error, nextStep)
        } yield result
      }

      def performAction(status: RetryStatus): M[A] =
        AH.handleWith(action) { error =>
          if (isWorthRetrying(error)) handleError(error, status)
          else AH.raise(error)
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
        M: Monad[M],
        AH: ApplicativeHandle[M, E],
        S: Sleep[M]
    ): M[A] = {
      retryingOnSomeErrors[A].apply[M, E](policy, _ => true, onError)(action)
    }
  }

}
