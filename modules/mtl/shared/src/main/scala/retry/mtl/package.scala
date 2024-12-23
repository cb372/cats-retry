package retry

import cats.effect.Temporal
import cats.mtl.Handle
import cats.syntax.apply.*
import cats.syntax.flatMap.*
import cats.syntax.functor.*

package object mtl:

  def retryingOnSomeErrors[A] = new RetryingOnSomeErrorsPartiallyApplied[A]

  def retryingOnAllErrors[A] = new RetryingOnAllErrorsPartiallyApplied[A]

  private[retry] class RetryingOnSomeErrorsPartiallyApplied[A]:

    def apply[F[_], E](
        policy: RetryPolicy[F],
        isWorthRetrying: E => F[Boolean],
        onError: (E, RetryDetails) => F[Unit]
    )(
        action: => F[A]
    )(using
        AH: Handle[F, E],
        T: Temporal[F]
    ): F[A] =
      T.tailRecM(RetryStatus.NoRetriesYet) { status =>
        AH.attempt(action).flatMap {
          case Left(error) =>
            def stopRecursion: F[Either[RetryStatus, A]] =
              AH.raise[E, A](error).map(Right(_))
            def runRetry: F[Either[RetryStatus, A]] =
              for
                nextStep <- applyPolicy(policy, status)
                _        <- onError(error, buildRetryDetails(status, nextStep))
                result <- nextStep match
                  case NextStep.RetryAfterDelay(delay, updatedStatus) =>
                    T.sleep(delay) *>
                      T.pure(Left(updatedStatus)) // continue recursion
                  case NextStep.GiveUp =>
                    AH.raise[E, A](error).map(Right(_)) // stop the recursion
              yield result

            isWorthRetrying(error).ifM(runRetry, stopRecursion)
          case Right(success) =>
            T.pure(Right(success)) // stop the recursion
        }
      }
  end RetryingOnSomeErrorsPartiallyApplied

  private[retry] class RetryingOnAllErrorsPartiallyApplied[A]:
    def apply[F[_], E](
        policy: RetryPolicy[F],
        onError: (E, RetryDetails) => F[Unit]
    )(
        action: => F[A]
    )(using
        AH: Handle[F, E],
        T: Temporal[F]
    ): F[A] =
      mtl
        .retryingOnSomeErrors[A]
        .apply[F, E](policy, _ => T.pure(true), onError)(
          action
        )
end mtl
