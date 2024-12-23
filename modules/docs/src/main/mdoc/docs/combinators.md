---
layout: docs
title: Combinators
---

# Combinators

The library offers a few slightly different ways to wrap your operations with
retries.

## Cheat sheet

| Combinator                                                    | Context bound | Handles             |
| ------------------------------------------------------------- | ------------- | ------------------- |
| [`retryingOnFailures`](#retryingonfailures)                   | Temporal      | Failures            |
| [`retryingOnErrors`](#retryingonerrors)                       | Temporal      | Errors              |
| [`retryingOnFailuresAndErrors`](#retryingonfailuresanderrors) | Temporal      | Failures and errors |

More information on each combinator is provided below.

The context bound for all the combinators is Cats Effect `Temporal`, because we
need the ability to sleep between retries. This implies that your effect monad
needs to be Cats Effect `IO` or something similar.

## Failures vs errors

We make a distinction between "failures" and "errors", which deserves some
explanation.

An action with type `IO[HttpResponse]`, when executed, can result in one of
three things:
* *Success*, meaning it returned an HTTP response that we are happy with, e.g. a
  200 response
* *Failure*, meaning it returned an HTTP response but we are not happy with it,
  e.g. a 404 response
* *Error*, meaning it raised an exception, e.g. using `IO.raiseError(...)`

cats-retry lets you choose whether you want to retry on failures, on errors, or
both.

### Unrecoverable errors

Some errors are worth retrying, while others are so serious that it's not worth
retrying.

For example, if an HTTP request failed with a 500 response, it's probably worth
retrying, but if the server responded with a `401 Unauthorized`, it's probably
not. Retrying the same request would just result in another 401 response.

When an action raises an error, cats-retry lets you inspect the error and decide
whether you want to retry or bail out. See the
[`retryingOnErrors`](#retryingonerrors) combinator for more details.

## `retryingOnFailures`

To use `retryingOnFailures`, you pass in a value handler that decides whether
you are happy with the result or you want to retry.

The API (modulo some type-inference trickery) looks like this:

```scala
def retryingOnFailures[F[_]: Temporal, A](policy: RetryPolicy[F],
                                          valueHandler: ValueHandler[F, A])
                                         (action: F[A]): F[A]
```

You need to pass in:

- a retry policy
- a handler that decides whether the operation was successful, and does any
necessary logging
- the operation that you want to wrap with retries

For example, let's keep rolling a die until we get a six, using `IO`.

```scala mdoc
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import retry._

import scala.concurrent.duration._

val policy = RetryPolicies.constantDelay[IO](10.milliseconds)

val valueHandler: ValueHandler[IO, Int] = (value: Int, details: RetryDetails) =>
  value match
    case 6 =>
      IO.pure(HandlerDecision.Stop)   // successful result, stop retrying
    case failedValue =>
      IO.println(s"Rolled a $failedValue, retrying ...")
        .as(HandlerDecision.Continue) // keep trying, assuming the retry policy allows it

val loadedDie = util.LoadedDie(2, 5, 4, 1, 3, 2, 6)

val io = retryingOnFailures(policy, valueHandler){
  IO(loadedDie.roll())
}

io.unsafeRunSync()
```

There is also a helper for lifting a predicate into a `ValueHandler`:

```scala mdoc:nest
val io = retryingOnFailures(
  policy = policy,
  valueHandler = ResultHandler.retryUntilSuccessful(_ == 6, log = ResultHandler.noop)
){
  IO(loadedDie.roll())
}

io.unsafeRunSync()
```

## `retryingOnErrors`

This is useful when you want to retry on some or all errors raised in your
effect monad's error channel.

To use `retryingOnErrors`, you need to pass in a handler that decides whether a
given error is worth retrying.

The API (modulo some type-inference trickery) looks like this:

```scala
def retryingOnErrors[F[_]: Temporal, A](policy: RetryPolicy[F],
                                        errorHandler: ErrorHandler[F, A])
                                       (action: F[A]): F[A]
```

You need to pass in:

- a retry policy
- a handler that decides whether a given error is worth retrying, and does any
necessary logging
- the operation that you want to wrap with retries

For example, let's make a request for a cat gif using our flaky HTTP client,
retrying only if we get an `IOException`.

```scala mdoc:nest
import java.io.IOException

val httpClient = util.FlakyHttpClient()
val flakyRequest: IO[String] = IO(httpClient.getCatGif())

val errorHandler: ErrorHandler[IO, String] = (e: Throwable, retryDetails: RetryDetails) =>
  e match
    case _: IOException =>
      IO.pure(HandlerDecision.Continue) // worth retrying
    case _ =>
      IO.pure(HandlerDecision.Stop)     // not worth retrying

val io = retryingOnErrors(
  policy = RetryPolicies.limitRetries[IO](5),
  errorHandler = errorHandler
)(flakyRequest)

io.unsafeRunSync()
```

There is also a helper for the common case where you want to retry on all errors:

```scala mdoc:nest
val io = retryingOnErrors(
  policy = RetryPolicies.limitRetries[IO](5),
  errorHandler = ResultHandler.retryOnAllErrors(log = ResultHandler.noop)
)(flakyRequest)

io.unsafeRunSync()
```

## `retryingOnFailuresAndErrors`

This is a combination of `retryingOnFailures` and `retryingOnErrors`. It allows you
to specify failure conditions for both the results and errors that can occur.

To use `retryingOnFailuresAndErrors`, you need to pass in a handler that
decides whether a given result is a success, a failure, an error that's worth
retrying, or an unrecoverable error.

The API (modulo some type-inference trickery) looks like this:

```scala
def retryingOnFailuresAndErrors[F[_]: Temporal, A](policy: RetryPolicy[F],
                                                   errorOrValueHandler: ErrorOrValueHandler[F, A])
                                                  (action: F[A]): F[A]
```

You need to pass in:

- a retry policy
- a handler that inspects the value or error and decides whether to retry, and
does any necessary logging
- the operation that you want to wrap with retries

For example, let's make a request to an API to retrieve details for a record, which we will only retry if:

- A timeout exception occurs
- The record's details are incomplete pending future operations

```scala mdoc:nest
import java.util.concurrent.TimeoutException

val httpClient = util.FlakyHttpClient()
val flakyRequest: IO[String] = IO(httpClient.getRecordDetails("foo"))

val errorOrValueHandler: ErrorOrValueHandler[IO, String] =
  (result: Either[Throwable, String], retryDetails: RetryDetails) =>
    result match
      case Left(_: TimeoutException) =>
        IO.pure(HandlerDecision.Continue) // worth retrying
      case Left(_) =>
        IO.pure(HandlerDecision.Stop)     // not worth retrying
      case Right("pending") =>
        IO.pure(HandlerDecision.Continue) // failure, retry
      case Right(_) =>
        IO.pure(HandlerDecision.Stop)     // success

val io = retryingOnFailuresAndErrors(
  policy = RetryPolicies.limitRetries[IO](5),
  errorOrValueHandler = errorOrValueHandler
)(flakyRequest)

io.unsafeRunSync()
```

## Syntactic sugar

Cats-retry includes some syntactic sugar in order to reduce boilerplate.

Instead of calling the combinators and passing in your action, you can call them
as extension methods.

```scala mdoc:nest:silent
import retry.syntax.*

// To retry until you get a value you like
IO(loadedDie.roll()).retryingOnFailures(
  policy = RetryPolicies.limitRetries[IO](2),
  valueHandler = valueHandler
)

val httpClient = util.FlakyHttpClient()

// To retry on some or all errors
IO(httpClient.getCatGif()).retryingOnErrors(
  policy = RetryPolicies.limitRetries[IO](2),
  errorHandler = errorHandler
)

// To retry only on failures and some or all errors
IO(httpClient.getRecordDetails("foo")).retryingOnFailuresAndErrors(
  policy = RetryPolicies.limitRetries[IO](2),
  errorOrValueHandler = errorOrValueHandler
)
```
