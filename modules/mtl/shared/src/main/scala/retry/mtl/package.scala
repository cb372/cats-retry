package retry

import cats.Monad
import cats.mtl.ApplicativeHandle
import cats.syntax.apply._
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

      M.tailRecM(RetryStatus.NoRetriesYet) { status =>
        AH.attempt(action).flatMap {
          case Left(error) if isWorthRetrying(error) =>
            for {
              nextStep <- applyPolicy(policy, status)
              _        <- onError(error, buildRetryDetails(status, nextStep))
              result <- nextStep match {
                case NextStep.RetryAfterDelay(delay, updatedStatus) =>
                  S.sleep(delay) *>
                    M.pure(Left(updatedStatus)) // continue recursion
                case NextStep.GiveUp =>
                  AH.raise[A](error).map(Right(_)) // stop the recursion
              }
            } yield result
          case Left(error) =>
            AH.raise[A](error).map(Right(_)) // stop the recursion
          case Right(success) =>
            M.pure(Right(success)) // stop the recursion
        }
      }

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
