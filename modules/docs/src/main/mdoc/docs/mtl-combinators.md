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

````scala mdoc:passthrough
println(
  s"""
  |```
  |val catsRetryVersion = "${retry.BuildInfo.version.replaceFirst("\\+.*", "")}"
  |libraryDependencies += "com.github.cb372" %% "cats-retry-mtl" % catsRetryVersion
  |```
  |""".stripMargin.trim
)
````

## Interaction with cats-retry core combinators

MTL retry works independently from `retry.retryingOnErrors`. The
`retry.mtl.retryingOnErrors` combinator evaluates retry exclusively on errors
produced by `Handle`. Thus errors produced in the effect monad's error channel
are not taken into account and retry is not triggered.

If you want to retry in case of any error, you can chain the methods:

```scala
action
  .retryingOnErrors(policy, exceptionHandler)
  .retryingOnMtlErrors[AppError](policy, mtlErrorHandler)
```

## `retryingOnErrors`

This is useful when you are working with a `Handle[M, E]` and you want to retry
on some or all errors.

To use `retryingOnErrors`, you need to pass in a predicate that decides
whether a given error is worth retrying.

The API (modulo some type-inference trickery) looks like this:

```scala
def retryingOnErrors[F[_]: Temporal, A, E: Handle[F, *]](
  policy: RetryPolicy[F],
  errorHandler: ResultHandler[F, E, A]
)(action: => F[A]): F[A]
```

You need to pass in:

- a retry policy
- an error that decides whether a given error is worth retrying, and does any
necessary logging
- the operation that you want to wrap with retries

Example:

```scala mdoc:silent
import retry.{HandlerDecision, ResultHandler, RetryDetails, RetryPolicies}
import cats.data.EitherT
import cats.effect.{Sync, IO}
import cats.mtl.Handle
import cats.syntax.all.*
import scala.concurrent.duration.*
import cats.effect.unsafe.implicits.global

type Effect[A] = EitherT[IO, AppError, A]

case class AppError(reason: String)

def failingOperation[F[_]: [M[_]] =>> Handle[M, AppError]]: F[Unit] =
  Handle[F, AppError].raise(AppError("Boom!"))

def logError[F[_]: Sync](error: AppError, details: RetryDetails): F[Unit] =
  Sync[F].delay(println(s"Raised error $error. Details $details"))

val handler: ResultHandler[Effect, AppError, Unit] =
  (error: AppError, details: RetryDetails) =>
    logError[Effect](error, details).as(
      if error.reason.contains("Boom!") then HandlerDecision.Continue else
HandlerDecision.Stop
    )

val policy = RetryPolicies.limitRetries[Effect](2)
```

```scala mdoc
retry.mtl.retryingOnErrors(policy, handler)(failingOperation[Effect])
  .value
  .unsafeRunTimed(1.second)
```

## Syntactic sugar

Cats-retry-mtl include some syntactic sugar in order to reduce boilerplate.

```scala mdoc:reset:silent
import retry.*
import cats.data.EitherT
import cats.effect.{Async, LiftIO, IO}
import cats.syntax.all.*
import cats.mtl.Handle
import retry.mtl.syntax.*
import retry.syntax.*
import scala.concurrent.duration.*
import cats.effect.unsafe.implicits.global

case class AppError(reason: String)

class Service[F[_]](client: util.FlakyHttpClient)(implicit F: Async[F], L: LiftIO[F], AH: Handle[F, AppError]) {

  // evaluates retry exclusively on errors produced by Handle
  def findCoolCatGifRetryMtl(policy: RetryPolicy[F]): F[String] =
    findCoolCatGif.retryingOnMtlErrors[AppError](policy, logAndRetryOnAllMtlErrors)

  // evaluates retry on errors produced by MonadError and Handle
  def findCoolCatGifRetryAll(policy: RetryPolicy[F]): F[String] =
    findCoolCatGif
      .retryingOnErrors(policy, logAndRetryOnAllErrors)
      .retryingOnMtlErrors[AppError](policy, logAndRetryOnAllMtlErrors)

  private def findCoolCatGif: F[String] =
    for {
      gif <- L.liftIO(client.getCatGif)
      _ <- isCoolGif(gif)
    } yield gif

  private def isCoolGif(string: String): F[Unit] =
    if (string.contains("cool")) F.unit
    else AH.raise(AppError("Gif is not cool"))

  private def logError(error: Throwable, details: RetryDetails): F[Unit] =
    F.delay(println(s"Raised error $error. Details $details"))

  private def logMtlError(error: AppError, details: RetryDetails): F[Unit] =
    F.delay(println(s"Raised MTL error $error. Details $details"))

  private val logAndRetryOnAllErrors: ErrorHandler[F, String] =
    (error: Throwable, details: RetryDetails) =>
      logError(error, details).as(HandlerDecision.Continue)

  private val logAndRetryOnAllMtlErrors: ResultHandler[F, AppError, String] =
    (error: AppError, details: RetryDetails) =>
      logMtlError(error, details).as(HandlerDecision.Continue)
      
}

type Effect[A] = EitherT[IO, AppError, A]

val policy = RetryPolicies.limitRetries[Effect](5)

val service = new Service[Effect](util.FlakyHttpClient())
```

Retrying only on MTL errors:

```scala mdoc
service.findCoolCatGifRetryMtl(policy).value.attempt.unsafeRunTimed(1.second)
```

Retrying on both exceptions and MTL errors:

```scala mdoc
service.findCoolCatGifRetryAll(policy).value.attempt.unsafeRunTimed(1.second)
```
