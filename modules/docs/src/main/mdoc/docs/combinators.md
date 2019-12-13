---
layout: docs
title: Combinators
---

# Combinators

The library offers a few slightly different ways to wrap your operations with
retries.

## `retryingM`

To use `retryingM`, you pass in a predicate that decides whether you are
happy with the result or you want to retry.
It is useful when you are working in an arbitrary `Monad` that is not a `MonadError`.
Your operation doesn't throw errors, but you want to retry until it returns a value that you are happy with.

The API (modulo some type-inference trickery) looks like this:

```scala
def retryingM[M[_]: Monad, A](policy: RetryPolicy[M],
                              wasSuccessful: A => Boolean,
                              onFailure: (A, RetryDetails) => M[Unit])
                             (action: => M[A]): M[A]
```

You need to pass in:

* a retry policy
* a predicate that decides whether the operation was successful
* a failure handler, often used for logging
* the operation that you want to wrap with retries

For example, let's redo the rolling-a-six example, this time using `IO`.

```scala mdoc:reset-class
import cats.effect.{ContextShift, IO, Timer}
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.global
import retry._

// We need an implicit cats.effect.Timer
implicit val timer: Timer[IO] = IO.timer(global)

val ioPolicy = RetryPolicies.constantDelay[IO](10.milliseconds)

def ioOnFailure(failedValue: Int, details: RetryDetails): IO[Unit] = {
  IO(println(s"Rolled a $failedValue, retrying ..."))
}
val loadedDie = util.LoadedDie(2, 5, 4, 1, 3, 2, 6)

val io = retryingM(ioPolicy, (_: Int) == 6, ioOnFailure){
  IO(loadedDie.roll())
}

// We need ContextShift for calling `.timeout`
implicit def ctx: ContextShift[IO] = IO.contextShift(global)

io.timeout(1.minute)
```

## `retryingOnSomeErrors`

This is useful when you are working with a `MonadError[M, E]` but you only want
to retry on some errors.

To use `retryingOnSomeErrors`, you need to pass in a predicate that decides whether a given error is worth retrying.

The API (modulo some type-inference trickery) looks like this:

```scala
def retryingOnSomeErrors[M[_]: Monad, A, E](policy: RetryPolicy[M],
                                            isWorthRetrying: E => Boolean,
                                            onError: (E, RetryDetails) => M[Unit])
                                           (action: => M[A]): M[A]
```

You need to pass in:

* a retry policy
* a predicate that decides whether a given error is worth retrying
* an error handler, often used for logging
* the operation that you want to wrap with retries

TODO example

## `retryingOnAllErrors`

This is useful when you are working with a `MonadError[M, E]` and you want to
retry on all errors.

The API (modulo some type-inference trickery) looks like this:

```scala
def retryingOnAllErrors[M[_]: Monad, A, E](policy: RetryPolicy[M],
                                           onError: (E, RetryDetails) => M[Unit])
                                          (action: => M[A]): M[A]
```

You need to pass in:

* a retry policy
* an error handler, often used for logging
* the operation that you want to wrap with retries

TODO example

## Syntactic sugar

Cats-retry include some syntactic sugar in order to reduce boilerplate.

```scala mdoc
import retry._
import cats.effect.IO
import retry.syntax.all._

val policy = RetryPolicies.limitRetries[IO](2)

val httpClient = util.FlakyHttpClient()

val flakyRequest: IO[String] = IO(httpClient.getCatGif())

flakyRequest.retryingOnAllErrors(policy, onError = retry.noop[IO, Throwable])
```
