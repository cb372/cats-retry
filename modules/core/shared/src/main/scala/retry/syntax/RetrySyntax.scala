package retry.syntax

import cats.effect.Temporal
import retry.*

extension [F[_], A](action: => F[A])

  def retryingOnFailures(
      policy: RetryPolicy[F, A],
      valueHandler: ValueHandler[F, A]
  )(using T: Temporal[F]): F[Either[A, A]] =
    retry.retryingOnFailures(action)(
      policy = policy,
      valueHandler = valueHandler
    )

  def retryingOnErrors(
      policy: RetryPolicy[F, Throwable],
      errorHandler: ErrorHandler[F, A]
  )(using T: Temporal[F]): F[A] =
    retry.retryingOnErrors(action)(
      policy = policy,
      errorHandler = errorHandler
    )

  def retryingOnFailuresAndErrors(
      policy: RetryPolicy[F, Either[Throwable, A]],
      errorOrValueHandler: ErrorOrValueHandler[F, A]
  )(using T: Temporal[F]): F[Either[A, A]] =
    retry.retryingOnFailuresAndErrors(action)(
      policy = policy,
      errorOrValueHandler = errorOrValueHandler
    )
