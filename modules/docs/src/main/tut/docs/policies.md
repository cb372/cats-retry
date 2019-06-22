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

Retry policies form a bounded semilattice (also known as commutative semilattice).

The empty element is a simple policy that retries with no delay and never gives
up.

The `combine` operation has the following semantics:

* If either of the policies wants to give up, the combined policy gives up.
* If both policies want to delay and retry, the *longer* of the two delays is
  chosen.

This way of combining policies implies:

* That combining two identical policies result in this same policy.
* That the order you combine policies doesn't affect the resulted policy.

A `BoundedSemilattice` instance is provided in the companion object, so you can compose
policies easily.

For example, to retry up to 5 times, starting with a 10 ms delay and increasing
exponentially up to a maximum of 1 second:

```tut:book
import cats.Id
import cats.syntax.semigroup._
import scala.concurrent.duration._
import retry.RetryPolicy
import retry.RetryPolicies._

val policy = limitRetries[Id](5) |+| capDelay(1.second, exponentialBackoff[Id](10.milliseconds))
```

There is also an operator `followedBy` to sequentially compose policies, i.e. if the first one wants to give up, use the second one.
As an example, we can retry with a 100ms delay 5 times and then retry every minute:

```tut:book
val retry5times100millis = constantDelay[Id](100.millis) |+| limitRetries[Id](5)

retry5times100millis.followedBy(constantDelay[Id](1.minute))
```

`followedBy` is an associative operation and forms a `Monoid` with a policy that always gives up as its identity:

```tut:book
// This is equal to just calling constantDelay[Id](200.millis)
constantDelay[Id](200.millis).followedBy(RetryPolicy.alwaysGiveUp)
```

Currently we don't provide such an instance as it would clash with the `BoundedSemilattice` instance described earlier.


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
