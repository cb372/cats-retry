package retry.mtl.syntax

import cats.MonadError
import cats.mtl.ApplicativeHandle
import retry.{RetryDetails, RetryPolicy, Sleep}

trait RetrySyntax {
  implicit final def retrySyntaxError[M[_], A, ME](
      action: => M[A]
  )(implicit M: MonadError[M, ME]): RetryingErrorOps[M, A, ME] =
    new RetryingErrorOps[M, A, ME](action)
}

final class RetryingErrorOps[M[_], A, ME](action: => M[A])(
    implicit M: MonadError[M, ME]
) {
  def retryingOnAllErrors[AH](
      policy: RetryPolicy[M],
      onError: (Either[ME, AH], RetryDetails) => M[Unit]
  )(implicit S: Sleep[M], AH: ApplicativeHandle[M, AH]): M[A] =
    retry.mtl.retryingOnAllErrors(
      policy = policy,
      onError = onError
    )(action)

  def retryingOnSomeErrors[AH](
      isWorthRetrying: Either[ME, AH] => Boolean,
      policy: RetryPolicy[M],
      onError: (Either[ME, AH], RetryDetails) => M[Unit]
  )(implicit S: Sleep[M], AH: ApplicativeHandle[M, AH]): M[A] =
    retry.mtl.retryingOnSomeErrors(
      policy = policy,
      isWorthRetrying = isWorthRetrying,
      onError = onError
    )(action)
}
