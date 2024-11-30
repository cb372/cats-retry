package retry.syntax

import cats.effect.Temporal
import retry.{ResultHandler, RetryPolicy}

trait RetrySyntax:
  implicit final def retrySyntaxBase[M[_], A](
      action: => M[A]
  ): RetryingOps[M, A] =
    new RetryingOps[M, A](action)

  implicit final def retrySyntaxError[M[_], A](
      action: => M[A]
  ): RetryingErrorOps[M, A] =
    new RetryingErrorOps[M, A](action)

final class RetryingOps[M[_], A](action: => M[A]):
  def retryingOnFailures(
      policy: RetryPolicy[M],
      resultHandler: ResultHandler[M, A, A]
  )(using T: Temporal[M]): M[A] =
    retry.retryingOnFailures(
      policy = policy,
      resultHandler = resultHandler
    )(action)

final class RetryingErrorOps[M[_], A](action: => M[A]):
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
