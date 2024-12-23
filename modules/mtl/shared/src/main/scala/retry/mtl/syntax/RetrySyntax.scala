package retry.mtl.syntax

import cats.effect.Temporal
import cats.mtl.Handle
import retry.{RetryDetails, RetryPolicy}

extension [F[_], A](action: F[A])
  def retryingOnAllMtlErrors[E](
      policy: RetryPolicy[F],
      onError: (E, RetryDetails) => F[Unit]
  )(using T: Temporal[F], AH: Handle[F, E]): F[A] =
    retry.mtl.retryingOnAllErrors(
      policy = policy,
      onError = onError
    )(action)

  def retryingOnSomeMtlErrors[E](
      isWorthRetrying: E => F[Boolean],
      policy: RetryPolicy[F],
      onError: (E, RetryDetails) => F[Unit]
  )(using T: Temporal[F], AH: Handle[F, E]): F[A] =
    retry.mtl.retryingOnSomeErrors(
      policy = policy,
      isWorthRetrying = isWorthRetrying,
      onError = onError
    )(action)
