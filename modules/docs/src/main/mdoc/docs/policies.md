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

`cats-retry` offers several ways of composing policies together.

### join

First up is the `join` operation, it has the following semantics:

* If either of the policies wants to give up, the combined policy gives up.
* If both policies want to delay and retry, the *longer* of the two delays is
  chosen.

This way of combining policies implies:

* That combining two identical policies result in this same policy.
* That the order you combine policies doesn't affect the resulted policy.

That is to say, `join` is associative, commutative and idempotent, which makes it a `Semilattice`.
Furthermore, it also forms a `BoundedSemilattice`, as there is also a neutral element for combining with `join`, which is a simple policy that retries with no delay and never gives up.
This makes it very useful for combining two policies with a lower bounded delay.

For an example of composing policies like this, we can use `join` to create a policy that retries up to 5 times, starting with a 10 ms delay and increasing
exponentially:

```scala mdoc:silent
import cats._
import cats.effect.IO
import cats.implicits._
import scala.concurrent.duration._
import retry.RetryPolicy
import retry.RetryPolicies._

val policy = limitRetries[IO](5) join exponentialBackoff[IO](10.milliseconds)
```

### meet

The next operation is `meet`, it is the dual of `join` and has the following semantics:

* If *both* of the policies wants to give up, the combined policy gives up.
* If both policies want to delay and retry, the *shorter* of the two delays is
  chosen.

Just like `join`, `meet` is also associative, commutative and idempotent, which implies:

* That combining two identical policies result in this same policy.
* That the order you combine policies doesn't affect the resulted policy.

You can use `meet` to compose policies where you want an upper bound on the delay.
As an example the `capDelay` combinator is implemented using `meet`:

```scala mdoc:silent
def capDelay[M[_]: Applicative](cap: FiniteDuration, policy: RetryPolicy[M]): RetryPolicy[M] =
  policy meet constantDelay[M](cap)

val neverAbove5Minutes = capDelay(5.minutes, exponentialBackoff[IO](10.milliseconds))
```

Retry policies form a distributive lattice, as `meet` and `join` both distribute over each other.

As we feel that the `join` operation is more common,
we use it as the canonical `BoundedSemilattice` instance found in the companion object.
This means you can use it with the standard Cats semigroup syntax like this:

```scala mdoc:silent

limitRetries[IO](5) |+| constantDelay[IO](100.milliseconds)
```

### followedBy

There is also an operator `followedBy` to sequentially compose policies, i.e. if the first one wants to give up, use the second one.
As an example, we can retry with a 100ms delay 5 times and then retry every minute:

```scala mdoc
val retry5times100millis = constantDelay[IO](100.millis) |+| limitRetries[IO](5)

retry5times100millis.followedBy(constantDelay[IO](1.minute))
```

`followedBy` is an associative operation and forms a `Monoid` with a policy that always gives up as its identity:

```scala mdoc:silent
// This is equal to just calling constantDelay[IO](200.millis)
constantDelay[IO](200.millis).followedBy(alwaysGiveUp)
```

Currently we don't provide such an instance, as it would clash with the `BoundedSemilattice` instance described earlier.

### mapDelay, flatMapDelay

The `mapDelay` and `flatMapDelay` operators work just like `map` and `flatMap`, but allow you to map on the `FiniteDuration` that represents the retry delay.

As a simple example, it allows us to add a specific delay of 10ms on top of an existing policy:

```scala mdoc
fibonacciBackoff[IO](200.millis).mapDelay(_ + 10.millis)
```

Furthermore, `flatMapDelay` also allows us to depend on certain effects to evaluate how to modify the delay:

```scala mdoc

def determineDelay: IO[FiniteDuration] = ???

fibonacciBackoff[IO](200.millis).flatMapDelay { currentDelay =>
  if (currentDelay < 500.millis) currentDelay.pure[IO]
  else determineDelay
}
```

### mapK

If you've defined a `RetryPolicy[F]`, but you need a `RetryPolicy` for another effect type `G[_]`, you can use `mapK` to convert from one to the other.
For example, you might have defined a custom `RetryPolicy[cats.effect.IO]` and for another part of the app you might need a `RetryPolicy[Kleisli[monix.eval.Task]]`:

```scala mdoc
import cats.effect.LiftIO
import cats.data.Kleisli
import monix.eval.Task

val customPolicy: RetryPolicy[IO] = 
  limitRetries[IO](5).join(constantDelay[IO](100.milliseconds))

customPolicy.mapK[Kleisli[Task, String, ?]](LiftIO.liftK[Kleisli[Task, String, ?]])
```


## Writing your own policy

The easiest way to define a custom retry policy is to use `RetryPolicy.lift`,
specifying the monad you need to work in:

```scala mdoc
import retry.{RetryPolicy, PolicyDecision}
import java.time.{LocalDate, DayOfWeek}

val onlyRetryOnTuesdays = RetryPolicy.lift[IO] { _ =>
  if (LocalDate.now().getDayOfWeek() == DayOfWeek.TUESDAY) {
    PolicyDecision.DelayAndRetry(delay = 100.milliseconds)
  } else {
    PolicyDecision.GiveUp
  }
}
```
