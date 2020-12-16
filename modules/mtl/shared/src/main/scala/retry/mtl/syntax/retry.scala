package retry.mtl.syntax

import cats.Monad
import cats.mtl.ApplicativeHandle
import retry.{RetryDetails, RetryPolicy, Sleep}

trait RetrySyntax {
  implicit final def retrySyntaxMtlError[M[_], A](
      action: => M[A]
  )(implicit M: Monad[M]): RetryingMtlErrorOps[M, A] =
    new RetryingMtlErrorOps[M, A](action)
}

final class RetryingMtlErrorOps[M[_], A](action: => M[A])(implicit
    M: Monad[M]
) {

  def retryingOnAllMtlErrors[E](
      policy: RetryPolicy[M],
      onError: (E, RetryDetails) => M[Unit]
  )(implicit S: Sleep[M], AH: ApplicativeHandle[M, E]): M[A] =
    retry.mtl.retryingOnAllErrors(
      policy = policy,
      onError = onError
    )(action)

  def retryingOnSomeMtlErrors[E](
      isWorthRetrying: E => Boolean,
      policy: RetryPolicy[M],
      onError: (E, RetryDetails) => M[Unit]
  )(implicit S: Sleep[M], AH: ApplicativeHandle[M, E]): M[A] =
    retry.mtl.retryingOnSomeErrors(
      policy = policy,
      isWorthRetrying = isWorthRetrying,
      onError = onError
    )(action)

}
