---
layout: docs
title: Combinators
---

# Combinators

The library offers a few slightly different ways to wrap your operations with
retries.

## Cheat sheet

| Combinator | Context bound | Handles |
| --- | --- | --- |
| [`retryingOnFailures`](#retryingOnFailures) | Monad | Failures |
| [`retryingOnSomeErrors`](#retryingonsomeerrors) | MonadError | Errors |
| [`retryingOnAllErrors`](#retryingonallerrors) | MonadError | Errors |
| [`retryingOnFailuresAndSomeErrors`](#retryingonfailuresandsomeerrors) | MonadError | Failures and errors |
| [`retryingOnFailuresAndAllErrors`](#retryingonfailuresandallerrors) | MonadError | Failures and errors |

More information on each combinator is provided below.

## `retryingOnFailures`

To use `retryingOnFailures`, you pass in a predicate that decides whether you are happy
with the result or you want to retry.  It is useful when you are working in an
arbitrary `Monad` that is not a `MonadError`.  Your operation doesn't throw
errors, but you want to retry until it returns a value that you are happy with.

The API (modulo some type-inference trickery) looks like this:

```scala
def retryingOnFailures[M[_]: Monad: Sleep, A](policy: RetryPolicy[M],
                                              wasSuccessful: A => M[Boolean],
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
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import retry._

import scala.concurrent.duration._

val policy = RetryPolicies.constantDelay[IO](10.milliseconds)

def onFailure(failedValue: Int, details: RetryDetails): IO[Unit] = {
  IO(println(s"Rolled a $failedValue, retrying ..."))
}

val loadedDie = util.LoadedDie(2, 5, 4, 1, 3, 2, 6)

val io = retryingOnFailures(policy, (i: Int) => IO.pure(i == 6), onFailure){
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
def retryingOnSomeErrors[M[_]: Sleep, A, E](policy: RetryPolicy[M],
                                            isWorthRetrying: E => M[Boolean],
                                            onError: (E, RetryDetails) => M[Unit])
                                           (action: => M[A])
                                           (implicit ME: MonadError[M, E]): M[A]
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

def isIOException(e: Throwable): IO[Boolean] = e match {
  case _: IOException => IO.pure(true)
  case _ => IO.pure(false)
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
def retryingOnAllErrors[M[_]: Sleep, A, E](policy: RetryPolicy[M],
                                           onError: (E, RetryDetails) => M[Unit])
                                          (action: => M[A])
                                          (implicit ME: MonadError[M, E]): M[A]
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

## `retryingOnFailuresAndSomeErrors`

This is a combination of `retryingOnFailures` and `retryingOnSomeErrors`. It allows you
to specify failure conditions for both the results and errors that can occur.

To use `retryingOnFailuresAndSomeErrors`, you need to pass in predicates that
decide whether a given error or result is worth retrying.

The API (modulo some type-inference trickery) looks like this:

```scala
def retryingOnFailuresAndSomeErrors[M[_]: Sleep, A, E](policy: RetryPolicy[M],
                                                       wasSuccessful: A => M[Boolean],
                                                       isWorthRetrying: E => M[Boolean],
                                                       onFailure: (A, RetryDetails) => M[Unit],
                                                       onError: (E, RetryDetails) => M[Unit])
                                                      (action: => M[A])
                                                      (implicit ME: MonadError[M, E]): M[A]
```

You need to pass in:

* a retry policy
* a predicate that decides whether the operation was successful
* a predicate that decides whether a given error is worth retrying
* a failure handler, often used for logging
* an error handler, often used for logging
* the operation that you want to wrap with retries

For example, let's make a request to an API to retrieve details for a record, which we will only retry if:
* A timeout exception occurs
* The record's details are incomplete pending future operations

```scala mdoc:nest
import java.util.concurrent.TimeoutException

val httpClient = util.FlakyHttpClient()
val flakyRequest: IO[String] = IO(httpClient.getRecordDetails("foo"))

def isTimeoutException(e: Throwable): IO[Boolean] = e match {
  case _: TimeoutException => IO.pure(true)
  case _ => IO.pure(false)
}

val io = retryingOnFailuresAndSomeErrors(
  wasSuccessful = (s: String) => IO.pure(s != "pending"),
  isWorthRetrying = isTimeoutException,
  policy = RetryPolicies.limitRetries[IO](5),
  onFailure = retry.noop[IO, String],
  onError = retry.noop[IO, Throwable]
)(flakyRequest)

io.unsafeRunSync()
```

## `retryingOnFailuresAndAllErrors`

This is a combination of `retryingOnFailures` and `retryingOnAllErrors`. It allows you to specify failure
conditions for your results as well as retry an error that occurs

To use `retryingOnFailuresAndAllErrors`, you need to pass in a predicate that decides
whether a given result is worth retrying.

The API (modulo some type-inference trickery) looks like this:

```scala
def retryingOnFailuresAndAllErrors[M[_]: Sleep, A, E](policy: RetryPolicy[M],
                                                      wasSuccessful: A => M[Boolean],
                                                      onFailure: (A, RetryDetails) => M[Unit],
                                                      onError: (E, RetryDetails) => M[Unit])
                                                     (action: => M[A])
                                                     (implicit ME: MonadError[M, E]): M[A]
```

You need to pass in:

* a retry policy
* a predicate that decides whether the operation was successful
* a failure handler, often used for logging
* an error handler, often used for logging
* the operation that you want to wrap with retries

For example, let's make a request to an API to retrieve details for a record, which we will only retry if:
* Any exception occurs
* The record's details are incomplete pending future operations

```scala mdoc:nest
import java.util.concurrent.TimeoutException

val httpClient = util.FlakyHttpClient()
val flakyRequest: IO[String] = IO(httpClient.getRecordDetails("foo"))

val io = retryingOnFailuresAndAllErrors(
  wasSuccessful = (s: String) => IO.pure(s != "pending"),
  policy = RetryPolicies.limitRetries[IO](5),
  onFailure = retry.noop[IO, String],
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
  wasSuccessful = (i: Int) => IO.pure(i == 6),
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

// To retry only on errors and results that are worth retrying
IO(httpClient.getRecordDetails("foo")).retryingOnFailuresAndSomeErrors(
  wasSuccessful = (s: String) => IO.pure(s != "pending"),
  isWorthRetrying = isTimeoutException,
  policy = RetryPolicies.limitRetries[IO](2),
  onFailure = retry.noop[IO, String],
  onError = retry.noop[IO, Throwable]
)

// To retry all errors and results that are worth retrying
IO(httpClient.getRecordDetails("foo")).retryingOnFailuresAndAllErrors(
  wasSuccessful = (s: String) => IO.pure(s != "pending"),
  policy = RetryPolicies.limitRetries[IO](2),
  onFailure = retry.noop[IO, String],
  onError = retry.noop[IO, Throwable]
)
```
