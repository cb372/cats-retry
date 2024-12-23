package retry.syntax

import retry.{RetryDetails, RetryPolicy}
import cats.effect.Temporal

extension [F[_], A](action: => F[A])

  def retryingOnFailures[E](
      wasSuccessful: A => F[Boolean],
      policy: RetryPolicy[F],
      onFailure: (A, RetryDetails) => F[Unit]
  )(using T: Temporal[F]): F[A] =
    retry.retryingOnFailures(
      policy = policy,
      wasSuccessful = wasSuccessful,
      onFailure = onFailure
    )(action)

  def retryingOnAllErrors(
      policy: RetryPolicy[F],
      onError: (Throwable, RetryDetails) => F[Unit]
  )(using T: Temporal[F]): F[A] =
    retry.retryingOnAllErrors(
      policy = policy,
      onError = onError
    )(action)

  def retryingOnSomeErrors(
      isWorthRetrying: Throwable => F[Boolean],
      policy: RetryPolicy[F],
      onError: (Throwable, RetryDetails) => F[Unit]
  )(using T: Temporal[F]): F[A] =
    retry.retryingOnSomeErrors(
      policy = policy,
      isWorthRetrying = isWorthRetrying,
      onError = onError
    )(action)

  def retryingOnFailuresAndAllErrors(
      wasSuccessful: A => F[Boolean],
      policy: RetryPolicy[F],
      onFailure: (A, RetryDetails) => F[Unit],
      onError: (Throwable, RetryDetails) => F[Unit]
  )(using T: Temporal[F]): F[A] =
    retry.retryingOnFailuresAndAllErrors(
      policy = policy,
      wasSuccessful = wasSuccessful,
      onFailure = onFailure,
      onError = onError
    )(action)

  def retryingOnFailuresAndSomeErrors(
      wasSuccessful: A => F[Boolean],
      isWorthRetrying: Throwable => F[Boolean],
      policy: RetryPolicy[F],
      onFailure: (A, RetryDetails) => F[Unit],
      onError: (Throwable, RetryDetails) => F[Unit]
  )(using T: Temporal[F]): F[A] =
    retry.retryingOnFailuresAndSomeErrors(
      policy = policy,
      wasSuccessful = wasSuccessful,
      isWorthRetrying = isWorthRetrying,
      onFailure = onFailure,
      onError = onError
    )(action)
end extension
