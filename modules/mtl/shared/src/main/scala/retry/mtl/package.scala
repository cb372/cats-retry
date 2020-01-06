package retry

import cats.mtl.ApplicativeHandle
import cats.{Applicative, MonadError}
import cats.syntax.functor._
import cats.syntax.flatMap._

package object mtl {

  def retryingOnSomeErrors[A] = new RetryingOnSomeErrorsPartiallyApplied[A]

  private[retry] class RetryingOnSomeErrorsPartiallyApplied[A] {
    def apply[M[_], ME, AH](
        policy: RetryPolicy[M],
        isWorthRetrying: Either[ME, AH] => Boolean,
        onError: (Either[ME, AH], RetryDetails) => M[Unit]
    )(
        action: => M[A]
    )(
        implicit
        ME: MonadError[M, ME],
        AH: ApplicativeHandle[M, AH],
        S: Sleep[M]
    ): M[A] = {
      def performNextStep(error: Either[ME, AH], nextStep: NextStep): M[A] =
        nextStep match {
          case NextStep.RetryAfterDelay(delay, updatedStatus) =>
            S.sleep(delay) >> performAction(updatedStatus)
          case NextStep.GiveUp =>
            error.fold(ME.raiseError, AH.raise)
        }

      def handleError(error: Either[ME, AH], status: RetryStatus): M[A] = {
        for {
          nextStep <- retry.applyPolicy(policy, status)
          _        <- onError(error, retry.buildRetryDetails(status, nextStep))
          result   <- performNextStep(error, nextStep)
        } yield result
      }

      def performAction(status: RetryStatus): M[A] =
        Attempt.attempt[M, ME, AH, A](action).flatMap {
          case Attempt.Result.MonadErrorRaised(cause)
              if isWorthRetrying(Left(cause)) =>
            handleError(Left(cause), status)
          case Attempt.Result.ApplicativeHandleRaised(cause)
              if isWorthRetrying(Right(cause)) =>
            handleError(Right(cause), status)
          case other =>
            Attempt.rethrow(other)
        }

      performAction(RetryStatus.NoRetriesYet)
    }
  }

  def retryingOnAllErrors[A] = new RetryingOnAllErrorsPartiallyApplied[A]

  private[retry] class RetryingOnAllErrorsPartiallyApplied[A] {
    def apply[M[_], ME, AH](
        policy: RetryPolicy[M],
        onError: (Either[ME, AH], RetryDetails) => M[Unit]
    )(
        action: => M[A]
    )(
        implicit
        ME: MonadError[M, ME],
        AH: ApplicativeHandle[M, AH],
        S: Sleep[M]
    ): M[A] = {
      retryingOnSomeErrors[A].apply[M, ME, AH](policy, _ => true, onError)(
        action
      )
    }
  }

  def noop[M[_]: Applicative, ME, AH]
      : (Either[ME, AH], RetryDetails) => M[Unit] =
    (_, _) => Applicative[M].pure(())

}
