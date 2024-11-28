package retry.syntax

import cats.{Monad, MonadError}
import retry.{ResultHandler, RetryDetails, RetryPolicy, Sleep}

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
  def retryingOnFailures[E](
      policy: RetryPolicy[M],
      resultHandler: ResultHandler[M, A, A]
  )(using
      M: Monad[M],
      S: Sleep[M]
  ): M[A] =
    retry.retryingOnFailures(
      policy = policy,
      resultHandler = resultHandler
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
      policy: RetryPolicy[M],
      errorHandler: ResultHandler[M, E, A]
  )(using S: Sleep[M]): M[A] =
    retry.retryingOnSomeErrors(
      policy = policy,
      errorHandler = errorHandler
    )(action)

  def retryingOnFailuresAndAllErrors(
      policy: RetryPolicy[M],
      resultHandler: ResultHandler[M, A, A],
      onError: (E, RetryDetails) => M[Unit]
  )(using S: Sleep[M]): M[A] =
    retry.retryingOnFailuresAndAllErrors(
      policy = policy,
      resultHandler = resultHandler,
      onError = onError
    )(action)

  def retryingOnFailuresAndSomeErrors(
      policy: RetryPolicy[M],
      resultOrErrorHandler: ResultHandler[M, Either[E, A], A]
  )(using S: Sleep[M]): M[A] =
    retry.retryingOnFailuresAndSomeErrors(
      policy = policy,
      resultOrErrorHandler = resultOrErrorHandler
    )(action)
end RetryingErrorOps
