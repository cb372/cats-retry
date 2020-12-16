---
layout: docs
title: MTL Combinators
---

# MTL Combinators

The `cats-retry-mtl` module provides two additional retry methods that operating
with errors produced by `Handle` from
[cats-mtl](https://github.com/typelevel/cats-mtl).

## Installation

To use `cats-retry-mtl`, add the following dependency to your `build.sbt`:

```scala mdoc:passthrough
println(
  s"""
  |```
  |val catsRetryVersion = "${retry.BuildInfo.version.replaceFirst("\\+.*", "")}"
  |libraryDependencies += "com.github.cb372" %% "cats-retry-mtl" % catsRetryVersion
  |```
  |""".stripMargin.trim
)
```

## Interaction with MonadError retry

MTL retry works independently from `retry.retryingOnSomeErrors`. The operations
`retry.mtl.retryingOnAllErrors` and `retry.mtl.retryingOnSomeErrors` evaluating
retry exclusively on errors produced by `Handle`.  Thus errors produced by
`MonadError` are not being taken into account and retry is not triggered.

If you want to retry in case of any error, you can chain the methods:

```scala
fa
  .retryingOnAllErrors(policy, onError = retry.noop[F, Throwable])
  .retryingOnAllMtlErrors[AppError](policy, onError = retry.noop[F, AppError])
```

## `retryingOnSomeErrors`

This is useful when you are working with an `Handle[M, E]` but you only want
to retry on some errors.

To use `retryingOnSomeErrors`, you need to pass in a predicate that decides
whether a given error is worth retrying.

The API (modulo some type-inference trickery) looks like this:

```scala
def retryingOnSomeErrors[M[_]: Monad: Sleep, A, E: Handle[M, *]](
  policy: RetryPolicy[M],
  isWorthRetrying: E => Boolean,
  onError: (E, RetryDetails) => M[Unit]
)(action: => M[A]): M[A]
```

You need to pass in:

* a retry policy
* a predicate that decides whether a given error is worth retrying
* an error handler, often used for logging
* the operation that you want to wrap with retries

Example:
```scala mdoc
import retry.{RetryDetails, RetryPolicies}
import cats.data.EitherT
import cats.effect.{Sync, IO, Timer}
import cats.mtl.Handle
import scala.concurrent.duration._

// We need an implicit cats.effect.Timer
implicit val timer: Timer[IO] = IO.timer(scala.concurrent.ExecutionContext.global)

type Effect[A] = EitherT[IO, AppError, A]

case class AppError(reason: String)

def failingOperation[F[_]: Handle[*[_], AppError]]: F[Unit] =
  Handle[F, AppError].raise(AppError("Boom!"))

def isWorthRetrying(error: AppError): Boolean =
  error.reason.contains("Boom!")

def logError[F[_]: Sync](error: AppError, details: RetryDetails): F[Unit] =
  Sync[F].delay(println(s"Raised error $error. Details $details"))

val policy = RetryPolicies.limitRetries[Effect](2)

retry.mtl
  .retryingOnSomeErrors(policy, isWorthRetrying, logError[Effect])(failingOperation[Effect])
  .value
  .unsafeRunTimed(1.second)
```

## `retryingOnAllErrors`

This is useful when you are working with a `Handle[M, E]` and you want to
retry on all errors.

The API (modulo some type-inference trickery) looks like this:

```scala
def retryingOnSomeErrors[M[_]: Monad: Sleep, A, E: Handle[M, *]](
  policy: RetryPolicy[M],
  onError: (E, RetryDetails) => M[Unit]
)(action: => M[A]): M[A]
```

You need to pass in:

* a retry policy
* an error handler, often used for logging
* the operation that you want to wrap with retries

Example:
```scala mdoc:reset
import retry.{RetryDetails, RetryPolicies}
import cats.data.EitherT
import cats.effect.{Sync, IO, Timer}
import cats.mtl.Handle
import scala.concurrent.duration._

// We need an implicit cats.effect.Timer
implicit val timer: Timer[IO] = IO.timer(scala.concurrent.ExecutionContext.global)

type Effect[A] = EitherT[IO, AppError, A]

case class AppError(reason: String)

def failingOperation[F[_]: Handle[*[_], AppError]]: F[Unit] =
  Handle[F, AppError].raise(AppError("Boom!"))

def logError[F[_]: Sync](error: AppError, details: RetryDetails): F[Unit] =
  Sync[F].delay(println(s"Raised error $error. Details $details"))

val policy = RetryPolicies.limitRetries[Effect](2)

retry.mtl
  .retryingOnAllErrors(policy, logError[Effect])(failingOperation[Effect])
  .value
  .unsafeRunTimed(1.second)
```

## Syntactic sugar

Cats-retry-mtl include some syntactic sugar in order to reduce boilerplate.

```scala mdoc:reset
import retry._
import cats.data.EitherT
import cats.effect.{Sync, IO, Timer}
import cats.syntax.functor._
import cats.syntax.flatMap._
import cats.mtl.Handle
import retry.mtl.syntax.all._
import retry.syntax.all._
import scala.concurrent.duration._

case class AppError(reason: String)

class Service[F[_]: Timer](client: util.FlakyHttpClient)(implicit F: Sync[F], AH: Handle[F, AppError]) {

  // evaluates retry exclusively on errors produced by Handle.
  def findCoolCatGifRetryMtl(policy: RetryPolicy[F]): F[String] =
    findCoolCatGif.retryingOnAllMtlErrors[AppError](policy, logMtlError)

  // evaluates retry on errors produced by MonadError and Handle
  def findCoolCatGifRetryAll(policy: RetryPolicy[F]): F[String] =
    findCoolCatGif
      .retryingOnAllErrors(policy, logError)
      .retryingOnAllMtlErrors[AppError](policy, logMtlError)

  private def findCoolCatGif: F[String] =
    for {
      gif <- findCatGif
      _ <- isCoolGif(gif)
    } yield gif

  private def findCatGif: F[String] =
    F.delay(client.getCatGif())

  private def isCoolGif(string: String): F[Unit] =
    if (string.contains("cool")) F.unit
    else AH.raise(AppError("Gif is not cool"))

  private def logError(error: Throwable, details: RetryDetails): F[Unit] =
    F.delay(println(s"Raised error $error. Details $details"))

  private def logMtlError(error: AppError, details: RetryDetails): F[Unit] =
    F.delay(println(s"Raised MTL error $error. Details $details"))
}

type Effect[A] = EitherT[IO, AppError, A]

implicit val timer: Timer[IO] = IO.timer(scala.concurrent.ExecutionContext.global)

val policy = RetryPolicies.limitRetries[Effect](5)

val service = new Service[Effect](util.FlakyHttpClient())

service.findCoolCatGifRetryMtl(policy).value.attempt.unsafeRunTimed(1.second)
service.findCoolCatGifRetryAll(policy).value.attempt.unsafeRunTimed(1.second)
```
