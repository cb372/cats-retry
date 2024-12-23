package retry.syntax

import cats.effect.Temporal
import retry.{ResultHandler, RetryPolicy}

extension [F[_], A](action: => F[A])

  def retryingOnFailures(
      policy: RetryPolicy[F],
      resultHandler: ResultHandler[F, A, A]
  )(using T: Temporal[F]): F[A] =
    retry.retryingOnFailures(
      policy = policy,
      resultHandler = resultHandler
    )(action)

  def retryingOnErrors(
      policy: RetryPolicy[F],
      errorHandler: ResultHandler[F, Throwable, A]
  )(using T: Temporal[F]): F[A] =
    retry.retryingOnErrors(
      policy = policy,
      errorHandler = errorHandler
    )(action)

  def retryingOnFailuresAndErrors(
      policy: RetryPolicy[F],
      resultOrErrorHandler: ResultHandler[F, Either[Throwable, A], A]
  )(using T: Temporal[F]): F[A] =
    retry.retryingOnFailuresAndErrors(
      policy = policy,
      resultOrErrorHandler = resultOrErrorHandler
    )(action)
