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

The API looks like this:

```scala
def retryingOnFailures[F[_]: Temporal, A](
    action: F[A]
)(
    policy: RetryPolicy[F],
    valueHandler: ValueHandler[F, A]
): F[Either[A, A]]
```

The inputs are:

- the operation that you want to wrap with retries
- a retry policy, which determines the maximum number of retries and how long to
  delay after each attempt
- a handler that decides whether the operation was successful, and does any
  necessary logging

The return value is one of:
- the successful value returned by the final attempt, wrapped in a `Right(...)`
  to indicate success
- the failed value returned by the final attempt before giving up, wrapped in a
  `Left(...)` to indicate failure
- an error raised in `F`, if either the action or the value handler raised an error

### Semantics

[![](https://mermaid.ink/img/pako:eNp1UstuwjAQ_BXLJ5DgB3KohAptDz1URFya5LCyN8SSY0d-0EZJ_r2ODRREm4N3Z_Y163igTHOkGa2l_mINGEfe96Ui4ctdQIsimmpJ1usnsvtG5h2m-BnMgXEPwqIlaIw2I9nNZlFEMnHV8q6muNQCc0KrKvVA542y5ATS40jeQHGJZnjWynrpSJPwlBqdo_8Nf0zKne5GknvG0NqgLQ4jNuHayzT2ovO2MghwQs2SPrQUrL8q6iL8Q9CGQ-dGEk1x6Dj8bpqSYyTeaGqZ2OTHDq_ihIeg9wWE9AaveuuAkd9rvSnbooR-o3jINv1IIkxJ0X34h_NBV7RF04Lg4RUMM1NS12CLJc2Cy7GGsGxJSzWFVPBO571iNHPG44oa7Y8NzWqQNiAfd90KOBpor2wH6lPrC55-AD8B1nA?type=png)](https://mermaid.live/edit#pako:eNp1UstuwjAQ_BXLJ5DgB3KohAptDz1URFya5LCyN8SSY0d-0EZJ_r2ODRREm4N3Z_Y163igTHOkGa2l_mINGEfe96Ui4ctdQIsimmpJ1usnsvtG5h2m-BnMgXEPwqIlaIw2I9nNZlFEMnHV8q6muNQCc0KrKvVA542y5ATS40jeQHGJZnjWynrpSJPwlBqdo_8Nf0zKne5GknvG0NqgLQ4jNuHayzT2ovO2MghwQs2SPrQUrL8q6iL8Q9CGQ-dGEk1x6Dj8bpqSYyTeaGqZ2OTHDq_ihIeg9wWE9AaveuuAkd9rvSnbooR-o3jINv1IIkxJ0X34h_NBV7RF04Lg4RUMM1NS12CLJc2Cy7GGsGxJSzWFVPBO571iNHPG44oa7Y8NzWqQNiAfd90KOBpor2wH6lPrC55-AD8B1nA)

### Example

For example, let's keep rolling a die until we get a six, using `IO`.

```scala mdoc:silent
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import retry.*

import scala.concurrent.duration.*

val loadedDie = util.LoadedDie(2, 5, 4, 1, 3, 2, 6)

val io = retryingOnFailures(loadedDie.roll)(
  policy = RetryPolicies.constantDelay(10.milliseconds),
  valueHandler = (value: Int, details: RetryDetails) =>
    value match
      case 6 =>
        IO.pure(HandlerDecision.Stop)   // successful result, stop retrying
      case failedValue =>
        IO(println(s"Rolled a $failedValue, retrying ..."))
          .as(HandlerDecision.Continue) // keep trying, as long as the retry policy allows it
)
```

```scala mdoc
io.unsafeRunSync()
```

There is also a helper for lifting a predicate into a `ValueHandler`:

```scala mdoc:nest:silent
val io = retryingOnFailures(loadedDie.roll)(
  policy = RetryPolicies.constantDelay(10.milliseconds),
  valueHandler = ResultHandler.retryUntilSuccessful(_ == 6, log = ResultHandler.noop)
)
```

```scala mdoc
io.unsafeRunSync()
```

## `retryingOnErrors`

This is useful when you want to retry on some or all errors raised in your
effect monad's error channel.

To use `retryingOnErrors`, you pass in a handler that decides whether a given
error is worth retrying.

The API looks like this:

```scala
def retryingOnErrors[F[_]: Temporal, A](
    action: F[A]
)(
    policy: RetryPolicy[F],
    errorHandler: ErrorHandler[F, A]
): F[A]
```

The inputs are:

- the operation that you want to wrap with retries
- a retry policy, which determines the maximum number of retries and how long to
  delay after each attempt
- a handler that decides whether a given error is worth retrying, and does any
  necessary logging

The return value is either:
- the value returned by the action, or
- an error raised in `F`, if
    - the action raised an error that the error handler judged to be unrecoverable
    - the action repeatedly raised errors and we ran out of retries
    - the error handler raised an error

### Semantics

[![](https://mermaid.ink/img/pako:eNptUU1vwjAM_StRTiDBH-hhEhpoO-wwUXFZy8FKXBopTap8sFWU_740BgYaudjP7_nFTk5cWIm84I2236IFF9jHtjYsnTIkNKty2M_ZcvnCNj8oYkDiL2Aixi2G6IxnR9ARR1ZGIdD7WUV15gk3UZNiP3-wqK5WIIKyZk-WoDx6hs5ZN7J3MFKjO71a46MOrCV8Jp8L-6RvM4U0x1Sk2vXu-54y2P6i_U-mK4My01qfVisx3GboM3wywkpCH0aWQ7XrJfytRuLM5BclS6pSnh3e1BF3jyPdsWvUMKyMTK_rhpFlSKKc3n8VX_AOXQdKpj8-TaKahxY7rHmRUokNpFVqXptzkkIMthyM4EVwERfc2XhoedGA9gnFvMlawcFBd6v2YL6sveLzL-oSyzM?type=png)](https://mermaid.live/edit#pako:eNptUU1vwjAM_StRTiDBH-hhEhpoO-wwUXFZy8FKXBopTap8sFWU_740BgYaudjP7_nFTk5cWIm84I2236IFF9jHtjYsnTIkNKty2M_ZcvnCNj8oYkDiL2Aixi2G6IxnR9ARR1ZGIdD7WUV15gk3UZNiP3-wqK5WIIKyZk-WoDx6hs5ZN7J3MFKjO71a46MOrCV8Jp8L-6RvM4U0x1Sk2vXu-54y2P6i_U-mK4My01qfVisx3GboM3wywkpCH0aWQ7XrJfytRuLM5BclS6pSnh3e1BF3jyPdsWvUMKyMTK_rhpFlSKKc3n8VX_AOXQdKpj8-TaKahxY7rHmRUokNpFVqXptzkkIMthyM4EVwERfc2XhoedGA9gnFvMlawcFBd6v2YL6sveLzL-oSyzM)

### Example

For example, let's make a request for a cat gif using our flaky HTTP client,
retrying only if we get an `IOException`.

```scala mdoc:nest:silent
import java.io.IOException

val httpClient = util.FlakyHttpClient()
val flakyRequest: IO[String] = httpClient.getCatGif

val io = retryingOnErrors(flakyRequest)(
  policy = RetryPolicies.limitRetries(5),
  errorHandler = (e: Throwable, retryDetails: RetryDetails) =>
    e match
      case _: IOException =>
        IO.pure(HandlerDecision.Continue) // worth retrying
      case _ =>
        IO.pure(HandlerDecision.Stop)     // not worth retrying
)
```

```scala mdoc
io.unsafeRunSync()
```

There is also a helper for the common case where you want to retry on all errors:

```scala mdoc:nest:silent
val io = retryingOnErrors(flakyRequest)(
  policy = RetryPolicies.limitRetries(5),
  errorHandler = ResultHandler.retryOnAllErrors(log = ResultHandler.noop)
)
```

```scala mdoc
io.unsafeRunSync()
```

## `retryingOnFailuresAndErrors`

This is a combination of `retryingOnFailures` and `retryingOnErrors`. It allows you
to specify failure conditions for both the results and errors that can occur.

To use `retryingOnFailuresAndErrors`, you need to pass in a handler that
decides whether a given result is a success, a failure, an error that's worth
retrying, or an unrecoverable error.

The API looks like this:

```scala
def retryingOnFailuresAndErrors[F[_]: Temporal, A](
    action: F[A]
)(
    policy: RetryPolicy[F],
    errorOrValueHandler: ErrorOrValueHandler[F, A]
): F[Either[A, A]]
```

The inputs are:

- the operation that you want to wrap with retries
- a retry policy, which determines the maximum number of retries and how long to
  delay after each attempt
- a handler that inspects the action's return value or error and decides whether
  to retry, and does any necessary logging

The return value is one of:
- the successful value returned by the final attempt, wrapped in a `Right(...)`
  to indicate success
- the failed value returned by the final attempt before giving up, wrapped in a
  `Left(...)` to indicate failure
- an error raised in `F`, if
    - the action raised an error that the error handler judged to be unrecoverable
    - the action repeatedly raised errors and we ran out of retries
    - the error handler raised an error

### Semantics

[![](https://mermaid.ink/img/pako:eNqVU8tugzAQ_BXLp0RKfoBDpahJ20OlVkHpoZCDhZdgydjIj7Qo5N9rbIIgCZHKxcx4ZtfL4BPOJAUc4ZzLn6wgyqD3bSqQe2Lj0Czxy36OlssntPmFzBoI-x1oN5otGKuERkfCLTToq13eiKAc1OlZCm25QUXA5-AeSnyJ2MiqQbHNMtB6loSKSAecWx5q7-cTdtfFMNE2_5ScZfWHeCGMWwV9_8rzU-1XlFSmQX7pvcmuosSNSDLDpNgH61jiv8tVy6C7In2XV3aEnRuz4_oxc4eBjke8518DJ_VKUGdTdYM8nBhoS5gGjUApqRq0aRfXriUDd2nTxZhc4uxmnarxONWh5H-nuDaG38HTE4rbxL3kft437nHefvtR2l4wynpwtBE1yvmhaDpM_zq8cXiBS1AlYdRd1VMrSrEpoIQUR-6VQk7cyClOxdlJiTUyrkWGI6MsLLCS9lDgKCdcO2T9lGtGDoqUPVsR8S3lBZ__AAneb50?type=png)](https://mermaid.live/edit#pako:eNqVU8tugzAQ_BXLp0RKfoBDpahJ20OlVkHpoZCDhZdgydjIj7Qo5N9rbIIgCZHKxcx4ZtfL4BPOJAUc4ZzLn6wgyqD3bSqQe2Lj0Czxy36OlssntPmFzBoI-x1oN5otGKuERkfCLTToq13eiKAc1OlZCm25QUXA5-AeSnyJ2MiqQbHNMtB6loSKSAecWx5q7-cTdtfFMNE2_5ScZfWHeCGMWwV9_8rzU-1XlFSmQX7pvcmuosSNSDLDpNgH61jiv8tVy6C7In2XV3aEnRuz4_oxc4eBjke8518DJ_VKUGdTdYM8nBhoS5gGjUApqRq0aRfXriUDd2nTxZhc4uxmnarxONWh5H-nuDaG38HTE4rbxL3kft437nHefvtR2l4wynpwtBE1yvmhaDpM_zq8cXiBS1AlYdRd1VMrSrEpoIQUR-6VQk7cyClOxdlJiTUyrkWGI6MsLLCS9lDgKCdcO2T9lGtGDoqUPVsR8S3lBZ__AAneb50)

### Example

For example, let's make a request to an API to retrieve details for a record, which we will only retry if:

- A timeout exception occurs
- The record's details are incomplete pending future operations

```scala mdoc:nest:silent
import java.util.concurrent.TimeoutException

val httpClient = util.FlakyHttpClient()
val flakyRequest: IO[String] = httpClient.getRecordDetails("foo")

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

val io = retryingOnFailuresAndErrors(flakyRequest)(
  policy = RetryPolicies.limitRetries(5),
  errorOrValueHandler = errorOrValueHandler
)
```

```scala mdoc
io.unsafeRunSync()
```

## Syntactic sugar

The cats-retry API is also available as extension methods.

You need to opt into this using an import:

```scala mdoc:silent
import retry.syntax.*
```

Examples:

```scala mdoc:nest:silent
// To retry until you get a value you like
loadedDie.roll.retryingOnFailures(
  policy = RetryPolicies.limitRetries(2),
  valueHandler = ResultHandler.retryUntilSuccessful(_ == 6, log = ResultHandler.noop)
)

val httpClient = util.FlakyHttpClient()

// To retry on some or all errors
httpClient.getCatGif.retryingOnErrors(
  policy = RetryPolicies.limitRetries(2),
  errorHandler = ResultHandler.retryOnAllErrors(log = ResultHandler.noop)
)

// To retry on failures and some or all errors
httpClient.getRecordDetails("foo").retryingOnFailuresAndErrors(
  policy = RetryPolicies.limitRetries(2),
  errorOrValueHandler = errorOrValueHandler
)
```
