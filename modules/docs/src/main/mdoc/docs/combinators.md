---
layout: docs
title: Combinators
---

# Combinators

The library offers a few slightly different ways to wrap your operations with
retries.

## `retryingOnFailures`

To use `retryingOnFailures`, you pass in a predicate that decides whether you are happy
with the result or you want to retry.  It is useful when you are working in an
arbitrary `Monad` that is not a `MonadError`.  Your operation doesn't throw
errors, but you want to retry until it returns a value that you are happy with.

The API (modulo some type-inference trickery) looks like this:

```scala
def retryingOnFailures[M[_]: Monad, A](policy: RetryPolicy[M],
                              wasSuccessful: A => Boolean,
                              onFailure: (A, RetryDetails) => M[Unit])
                             (action: => M[A]): M[A]
```

You need to pass in:

* a retry policy
* a predicate that decides whether the operation was successful
* a failure handler, often used for logging
* the operation that you want to wrap with retries

For example, let's keep rolling a die until we get a six, using `IO`.

```scala mdoc
import retry._

import cats.effect.{IO, Timer}
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.global

// We need an implicit cats.effect.Timer
implicit val timer: Timer[IO] = IO.timer(global)

val policy = RetryPolicies.constantDelay[IO](10.milliseconds)

def onFailure(failedValue: Int, details: RetryDetails): IO[Unit] = {
  IO(println(s"Rolled a $failedValue, retrying ..."))
}

val loadedDie = util.LoadedDie(2, 5, 4, 1, 3, 2, 6)

val io = retryingOnFailures(policy, (_: Int) == 6, onFailure){
  IO(loadedDie.roll())
}

io.unsafeRunSync()
```

## `retryingOnSomeErrors`

This is useful when you are working with a `MonadError[M, E]` but you only want
to retry on some errors.

To use `retryingOnSomeErrors`, you need to pass in a predicate that decides
whether a given error is worth retrying.

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

For example, let's make a request for a cat gif using our flaky HTTP client,
retrying only if we get an `IOException`.

```scala mdoc:nest
import java.io.IOException

val httpClient = util.FlakyHttpClient()
val flakyRequest: IO[String] = IO(httpClient.getCatGif())

def isIOException(e: Throwable): Boolean = e match {
  case _: IOException => true
  case _ => false
}

val io = retryingOnSomeErrors(
  isWorthRetrying = isIOException,
  policy = RetryPolicies.limitRetries[IO](5),
  onError = retry.noop[IO, Throwable]
)(flakyRequest)

io.unsafeRunSync()
```

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

For example, let's make the same request for a cat gif, this time retrying on
all errors.

```scala mdoc:nest
import java.io.IOException

val httpClient = util.FlakyHttpClient()
val flakyRequest: IO[String] = IO(httpClient.getCatGif())

val io = retryingOnAllErrors(
  policy = RetryPolicies.limitRetries[IO](5),
  onError = retry.noop[IO, Throwable]
)(flakyRequest)

io.unsafeRunSync()
```

## Syntactic sugar

Cats-retry includes some syntactic sugar in order to reduce boilerplate.

Instead of calling the combinators and passing in your action, you can call them
as extension methods.

```scala mdoc:nest:silent
import retry.syntax.all._

// To retry until you get a value you like
IO(loadedDie.roll()).retryingOnFailures(
  policy = RetryPolicies.limitRetries[IO](2),
  wasSuccessful = (_: Int) == 6,
  onFailure = retry.noop[IO, Int]
)

val httpClient = util.FlakyHttpClient()

// To retry only on errors that are worth retrying
IO(httpClient.getCatGif()).retryingOnSomeErrors(
  isWorthRetrying = isIOException,
  policy = RetryPolicies.limitRetries[IO](2),
  onError = retry.noop[IO, Throwable]
)

// To retry on all errors
IO(httpClient.getCatGif()).retryingOnAllErrors(
  policy = RetryPolicies.limitRetries[IO](2),
  onError = retry.noop[IO, Throwable]
)
```
