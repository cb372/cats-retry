package retry.mtl.syntax

import cats.effect.Temporal
import cats.mtl.Handle
import retry.{RetryDetails, RetryPolicy}

extension [M[_], A](action: M[A])
  def retryingOnAllMtlErrors[E](
      policy: RetryPolicy[M],
      onError: (E, RetryDetails) => M[Unit]
  )(using T: Temporal[M], AH: Handle[M, E]): M[A] =
    retry.mtl.retryingOnAllErrors(
      policy = policy,
      onError = onError
    )(action)

  def retryingOnSomeMtlErrors[E](
      isWorthRetrying: E => M[Boolean],
      policy: RetryPolicy[M],
      onError: (E, RetryDetails) => M[Unit]
  )(using T: Temporal[M], AH: Handle[M, E]): M[A] =
    retry.mtl.retryingOnSomeErrors(
      policy = policy,
      isWorthRetrying = isWorthRetrying,
      onError = onError
    )(action)
