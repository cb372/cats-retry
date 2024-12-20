package retry.syntax

import cats.effect.Temporal
import retry.{ResultHandler, RetryPolicy}

extension [M[_], A](action: => M[A])

  def retryingOnFailures(
      policy: RetryPolicy[M],
      resultHandler: ResultHandler[M, A, A]
  )(using T: Temporal[M]): M[A] =
    retry.retryingOnFailures(
      policy = policy,
      resultHandler = resultHandler
    )(action)

  def retryingOnErrors(
      policy: RetryPolicy[M],
      errorHandler: ResultHandler[M, Throwable, A]
  )(using T: Temporal[M]): M[A] =
    retry.retryingOnErrors(
      policy = policy,
      errorHandler = errorHandler
    )(action)

  def retryingOnFailuresAndErrors(
      policy: RetryPolicy[M],
      resultOrErrorHandler: ResultHandler[M, Either[Throwable, A], A]
  )(using T: Temporal[M]): M[A] =
    retry.retryingOnFailuresAndErrors(
      policy = policy,
      resultOrErrorHandler = resultOrErrorHandler
    )(action)
