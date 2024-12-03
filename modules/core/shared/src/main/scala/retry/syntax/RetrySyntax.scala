package retry.syntax

import retry.{RetryDetails, RetryPolicy}
import cats.effect.Temporal

extension [M[_], A](action: => M[A])

  def retryingOnFailures[E](
      wasSuccessful: A => M[Boolean],
      policy: RetryPolicy[M],
      onFailure: (A, RetryDetails) => M[Unit]
  )(using T: Temporal[M]): M[A] =
    retry.retryingOnFailures(
      policy = policy,
      wasSuccessful = wasSuccessful,
      onFailure = onFailure
    )(action)

  def retryingOnAllErrors(
      policy: RetryPolicy[M],
      onError: (Throwable, RetryDetails) => M[Unit]
  )(using T: Temporal[M]): M[A] =
    retry.retryingOnAllErrors(
      policy = policy,
      onError = onError
    )(action)

  def retryingOnSomeErrors(
      isWorthRetrying: Throwable => M[Boolean],
      policy: RetryPolicy[M],
      onError: (Throwable, RetryDetails) => M[Unit]
  )(using T: Temporal[M]): M[A] =
    retry.retryingOnSomeErrors(
      policy = policy,
      isWorthRetrying = isWorthRetrying,
      onError = onError
    )(action)

  def retryingOnFailuresAndAllErrors(
      wasSuccessful: A => M[Boolean],
      policy: RetryPolicy[M],
      onFailure: (A, RetryDetails) => M[Unit],
      onError: (Throwable, RetryDetails) => M[Unit]
  )(using T: Temporal[M]): M[A] =
    retry.retryingOnFailuresAndAllErrors(
      policy = policy,
      wasSuccessful = wasSuccessful,
      onFailure = onFailure,
      onError = onError
    )(action)

  def retryingOnFailuresAndSomeErrors(
      wasSuccessful: A => M[Boolean],
      isWorthRetrying: Throwable => M[Boolean],
      policy: RetryPolicy[M],
      onFailure: (A, RetryDetails) => M[Unit],
      onError: (Throwable, RetryDetails) => M[Unit]
  )(using T: Temporal[M]): M[A] =
    retry.retryingOnFailuresAndSomeErrors(
      policy = policy,
      wasSuccessful = wasSuccessful,
      isWorthRetrying = isWorthRetrying,
      onFailure = onFailure,
      onError = onError
    )(action)
end extension
