package retry.syntax

import cats.{Monad, MonadError}
import retry.{RetryDetails, RetryPolicy, Sleep}

trait RetrySyntax {
  implicit final def retrySyntaxBase[M[_], A](
      action: => M[A]): RetryingOps[M, A] =
    new RetryingOps[M, A](action)

}

final class RetryingOps[M[_], A](action: => M[A]) {

  def retryingM[E](wasSuccessful: A => Boolean)(
      implicit policy: RetryPolicy[M],
      onFailure: (A, RetryDetails) => M[Unit],
      Mo: Monad[M],
      S: Sleep[M]): M[A] =
    retry.retryingM(
      policy = policy,
      wasSuccessful = wasSuccessful,
      onFailure = onFailure
    )(action)

  def retryingOnAllErrors[E](implicit policy: RetryPolicy[M],
                             onError: (E, RetryDetails) => M[Unit],
                             ME: MonadError[M, E],
                             S: Sleep[M]): M[A] =
    retry.retryingOnAllErrors(
      policy = policy,
      onError = onError
    )(action)

  def retryingOnSomeErrors[E](isWorthRetrying: E => Boolean)(
      implicit policy: RetryPolicy[M],
      onError: (E, RetryDetails) => M[Unit],
      ME: MonadError[M, E],
      S: Sleep[M]): M[A] =
    retry.retryingOnSomeErrors(
      policy = policy,
      isWorthRetrying = isWorthRetrying,
      onError = onError
    )(action)

}
