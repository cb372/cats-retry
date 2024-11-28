package retry

import cats.Monad
import cats.mtl.Handle
import cats.syntax.apply.*
import cats.syntax.flatMap.*
import cats.syntax.functor.*

package object mtl:

  def retryingOnSomeErrors[A] = new RetryingOnSomeErrorsPartiallyApplied[A]

  def retryingOnAllErrors[A] = new RetryingOnAllErrorsPartiallyApplied[A]

  private[retry] class RetryingOnSomeErrorsPartiallyApplied[A]:

    def apply[M[_], E](
        policy: RetryPolicy[M],
        isWorthRetrying: E => M[Boolean],
        onError: (E, RetryDetails) => M[Unit]
    )(
        action: => M[A]
    )(implicit
        M: Monad[M],
        AH: Handle[M, E],
        S: Sleep[M]
    ): M[A] =
      M.tailRecM(RetryStatus.NoRetriesYet) { status =>
        AH.attempt(action).flatMap {
          case Left(error) =>
            def stopRecursion: M[Either[RetryStatus, A]] =
              AH.raise[E, A](error).map(Right(_))
            def runRetry: M[Either[RetryStatus, A]] =
              for
                nextStep <- applyPolicy(policy, status)
                _        <- onError(error, buildRetryDetails(status, nextStep))
                result <- nextStep match
                  case NextStep.RetryAfterDelay(delay, updatedStatus) =>
                    S.sleep(delay) *>
                      M.pure(Left(updatedStatus)) // continue recursion
                  case NextStep.GiveUp =>
                    AH.raise[E, A](error).map(Right(_)) // stop the recursion
              yield result

            isWorthRetrying(error).ifM(runRetry, stopRecursion)
          case Right(success) =>
            M.pure(Right(success)) // stop the recursion
        }
      }

  private[retry] class RetryingOnAllErrorsPartiallyApplied[A]:
    def apply[M[_], E](
        policy: RetryPolicy[M],
        onError: (E, RetryDetails) => M[Unit]
    )(
        action: => M[A]
    )(implicit
        M: Monad[M],
        AH: Handle[M, E],
        S: Sleep[M]
    ): M[A] =
      mtl
        .retryingOnSomeErrors[A]
        .apply[M, E](policy, _ => M.pure(true), onError)(
          action
        )
