package retry.mtl.syntax

import cats.Monad
import cats.mtl.Handle
import retry.{RetryDetails, RetryPolicy, Sleep}

trait RetrySyntax:
  // TODO how to migrate this to scala 3?
  implicit final def retrySyntaxMtlError[M[_], A](
      action: => M[A]
  )(using M: Monad[M]): RetryingMtlErrorOps[M, A] =
    new RetryingMtlErrorOps[M, A](action)

final class RetryingMtlErrorOps[M[_], A](action: => M[A])(using
    M: Monad[M]
):

  def retryingOnAllMtlErrors[E](
      policy: RetryPolicy[M],
      onError: (E, RetryDetails) => M[Unit]
  )(using S: Sleep[M], AH: Handle[M, E]): M[A] =
    retry.mtl.retryingOnAllErrors(
      policy = policy,
      onError = onError
    )(action)

  def retryingOnSomeMtlErrors[E](
      isWorthRetrying: E => M[Boolean],
      policy: RetryPolicy[M],
      onError: (E, RetryDetails) => M[Unit]
  )(using S: Sleep[M], AH: Handle[M, E]): M[A] =
    retry.mtl.retryingOnSomeErrors(
      policy = policy,
      isWorthRetrying = isWorthRetrying,
      onError = onError
    )(action)
