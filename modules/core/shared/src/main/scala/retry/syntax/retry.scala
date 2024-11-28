package retry.syntax

import cats.{Monad, MonadError}
import retry.{RetryDetails, RetryPolicy, Sleep}

trait RetrySyntax:
  // TODO how to translate these to methods to scala3?
  implicit final def retrySyntaxBase[M[_], A](
      action: => M[A]
  ): RetryingOps[M, A] =
    new RetryingOps[M, A](action)

  implicit final def retrySyntaxError[M[_], A, E](
      action: => M[A]
  )(using M: MonadError[M, E]): RetryingErrorOps[M, A, E] =
    new RetryingErrorOps[M, A, E](action)

final class RetryingOps[M[_], A](action: => M[A]):
  @deprecated("Use retryingOnFailures instead", "2.1.0")
  def retryingM[E](
      wasSuccessful: A => M[Boolean],
      policy: RetryPolicy[M],
      onFailure: (A, RetryDetails) => M[Unit]
  )(using
      M: Monad[M],
      S: Sleep[M]
  ): M[A] = retryingOnFailures(wasSuccessful, policy, onFailure)

  def retryingOnFailures[E](
      wasSuccessful: A => M[Boolean],
      policy: RetryPolicy[M],
      onFailure: (A, RetryDetails) => M[Unit]
  )(using
      M: Monad[M],
      S: Sleep[M]
  ): M[A] =
    retry.retryingOnFailures(
      policy = policy,
      wasSuccessful = wasSuccessful,
      onFailure = onFailure
    )(action)

final class RetryingErrorOps[M[_], A, E](action: => M[A])(using
    M: MonadError[M, E]
):
  def retryingOnAllErrors(
      policy: RetryPolicy[M],
      onError: (E, RetryDetails) => M[Unit]
  )(using S: Sleep[M]): M[A] =
    retry.retryingOnAllErrors(
      policy = policy,
      onError = onError
    )(action)

  def retryingOnSomeErrors(
      isWorthRetrying: E => M[Boolean],
      policy: RetryPolicy[M],
      onError: (E, RetryDetails) => M[Unit]
  )(using S: Sleep[M]): M[A] =
    retry.retryingOnSomeErrors(
      policy = policy,
      isWorthRetrying = isWorthRetrying,
      onError = onError
    )(action)

  def retryingOnFailuresAndAllErrors(
      wasSuccessful: A => M[Boolean],
      policy: RetryPolicy[M],
      onFailure: (A, RetryDetails) => M[Unit],
      onError: (E, RetryDetails) => M[Unit]
  )(using S: Sleep[M]): M[A] =
    retry.retryingOnFailuresAndAllErrors(
      policy = policy,
      wasSuccessful = wasSuccessful,
      onFailure = onFailure,
      onError = onError
    )(action)

  def retryingOnFailuresAndSomeErrors(
      wasSuccessful: A => M[Boolean],
      isWorthRetrying: E => M[Boolean],
      policy: RetryPolicy[M],
      onFailure: (A, RetryDetails) => M[Unit],
      onError: (E, RetryDetails) => M[Unit]
  )(using S: Sleep[M]): M[A] =
    retry.retryingOnFailuresAndSomeErrors(
      policy = policy,
      wasSuccessful = wasSuccessful,
      isWorthRetrying = isWorthRetrying,
      onFailure = onFailure,
      onError = onError
    )(action)
