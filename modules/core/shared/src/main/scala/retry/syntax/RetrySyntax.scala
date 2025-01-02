package retry.syntax

import cats.effect.Temporal
import retry.*

extension [F[_], A](action: => F[A])

  def retryingOnFailures(
      policy: RetryPolicy[F],
      valueHandler: ValueHandler[F, A]
  )(using T: Temporal[F]): F[A] =
    retry.retryingOnFailures(
      policy = policy,
      valueHandler = valueHandler
    )(action)

  def retryingOnErrors(
      policy: RetryPolicy[F],
      errorHandler: ErrorHandler[F, A]
  )(using T: Temporal[F]): F[A] =
    retry.retryingOnErrors(
      policy = policy,
      errorHandler = errorHandler
    )(action)

  def retryingOnFailuresAndErrors(
      policy: RetryPolicy[F],
      errorOrValueHandler: ErrorOrValueHandler[F, A]
  )(using T: Temporal[F]): F[A] =
    retry.retryingOnFailuresAndErrors(
      policy = policy,
      errorOrValueHandler = errorOrValueHandler
    )(action)
