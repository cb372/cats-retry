---
layout: docs
title: Retry policies
---

# Retry policies

A retry policy is a function that takes in a `RetryStatus` and returns a
`PolicyDecision` in a monoidal context:

```scala
case class RetryPolicy[M[_]](decideNextRetry: RetryStatus => M[PolicyDecision])
```

The policy decision can be one of two things:

1. we should delay for some amount of time, possibly zero, and then retry
2. we should stop retrying and give up

## Built-in policies

There are a number of policies available in `retry.RetryPolicies`, including:

* `constantDelay` (retry forever, with a fixed delay between retries)
* `limitRetries` (retry up to N times, with no delay between retries)
* `exponentialBackoff` (double the delay after each retry)
* `fibonacciBackoff` (`delay(n) = (delay(n - 2) + delay(n - 1)`)
* `fullJitter` (randomised exponential backoff)

## Policy transformers

There are also a few combinators to transform policies, including:

* `capDelay` (set an upper bound on the delay between retries)
* `limitRetriesByDelay` (give up when the delay between retries reaches a
  certain limit)
* `limitRetriesByCumulativeDelay` (give up when the total delay reaches a
  certain limit)

## Composing policies

Retry policies form a commutative monoid.

The empty element is a simple policy that retries with no delay and never gives
up.

The `combine` operation has the following semantics:

* If either of the policies wants to give up, the combined policy gives up.
* If both policies want to delay and retry, the *longer* of the two delays is
  chosen.

A `Monoid` instance is provided in the companion object, so you can compose
policies easily.

For example, to retry up to 5 times, starting with a 10 ms delay and increasing
exponentially up to a maximum of 1 second:

```tut:book
import cats.Id
import cats.syntax.monoid._
import scala.concurrent.duration._
import retry.RetryPolicies._

val policy = limitRetries[Id](5) |+| capDelay(1.second, exponentialBackoff[Id](10.milliseconds))
```

## Writing your own policy

The easiest way to define a custom retry policy is to use `RetryPolicy.lift`,
specifying the monad you need to work in:

```tut:book
import retry.{RetryPolicy, PolicyDecision}
import cats.effect.IO
import java.time.{LocalDate, DayOfWeek}

val onlyRetryOnTuesdays = RetryPolicy.lift[IO] { _ =>
  if (LocalDate.now().getDayOfWeek() == DayOfWeek.TUESDAY) {
    PolicyDecision.DelayAndRetry(delay = 100.milliseconds)
  } else {
    PolicyDecision.GiveUp
  }
}
```
