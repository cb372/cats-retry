---
layout: docs
title: Combinators
---

# Combinators

The library offers 3 slightly different ways to wrap your operations with
retries.

## `retrying`

This is useful when you are working in an arbitrary `Monad` that is not a
`MonadError`. Your operation doesn't throw errors, but you want to retry until
it returns a value that you are happy with.

To use `retrying`, you pass in a predicate that decides whether you are
happy with the result or you want to retry.

The API (modulo some type-inference trickery) looks like this:

```scala
def retrying[A, M: Monad](policy: RetryPolicy[M],
                          wasSuccessful: A => Boolean,
                          onFailure: (A, RetryDetails) => M[Unit])
                         (action: => M[A])
```

You need to pass in:

* a retry policy
* a predicate that decides whether the operation was successful
* a failure handler, often used for logging
* the operation that you want to wrap with retries

For example, let's keep rolling a die until we get a six.

To keep things simple, we'll work in the `Id` monad.

```tut:book
import cats.Id
import retry._
import scala.concurrent.duration._

val policy = RetryPolicies.constantDelay[Id](10.milliseconds)

val predicate = (_: Int) == 6

def onFailure(failedValue: Int, details: RetryDetails): Unit = {
  println(s"Rolled a $failedValue, retrying ...")
}
```

```tut
retrying[Int][Id](policy, predicate, onFailure){
  scala.util.Random.nextInt(6) + 1
}
```

## `retryingOnSomeErrors`

This is useful when you are working with a `MonadError[A, E]` but you only want
to retry on some errors.

To use `retryingOnSomeErrors`, you need to pass in a predicate that decides whether a given error is worth retrying.

The API (modulo some type-inference trickery) looks like this:

```scala
def retryingOnSomeErrors[A, E, M: Monad](policy: RetryPolicy[M],
                                         isWorthRetrying: A => Boolean,
                                         onError: (E, RetryDetails) => M[Unit])
                                        (action: => M[A])
```

You need to pass in:

* a retry policy
* a predicate that decides whether a given error is worth retrying
* an error handler, often used for logging
* the operation that you want to wrap with retries

TODO example

## `retryingOnAllErrors`

This is useful when you are working with a `MonadError[A, E]` and you want to
retry on all errors.

The API (modulo some type-inference trickery) looks like this:

```scala
def retryingOnAllErrors[A, E, M: Monad](policy: RetryPolicy[M],
                                        onError: (E, RetryDetails) => M[Unit])
                                       (action: => M[A])
```

You need to pass in:

* a retry policy
* an error handler, often used for logging
* the operation that you want to wrap with retries

TODO example
