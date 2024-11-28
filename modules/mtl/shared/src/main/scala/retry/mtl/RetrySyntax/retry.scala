package retry.mtl.RetrySyntax

import cats.MonadError
import cats.mtl.Handle
import retry.{RetryDetails, RetryPolicy, Sleep}

extension [M[_], A](action: M[A])

  def retryingOnAllMtlErrors[E](
      policy: RetryPolicy[M],
      onError: (E, RetryDetails) => M[Unit]
  )(using  M: MonadError[M, E], S: Sleep[M], AH: Handle[M, E]): M[A] =
    retry.mtl.retryingOnAllErrors(
      policy = policy,
      onError = onError
    )(action)

  def retryingOnSomeMtlErrors[E](
      isWorthRetrying: E => M[Boolean],
      policy: RetryPolicy[M],
      onError: (E, RetryDetails) => M[Unit]
  )(using M: MonadError[M, E], S: Sleep[M], AH: Handle[M, E]): M[A] =
    retry.mtl.retryingOnSomeErrors(
      policy = policy,
      isWorthRetrying = isWorthRetrying,
      onError = onError
    )(action)
