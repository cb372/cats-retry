---
layout: docs
title: Combinators
---

# Combinators

The library offers a few slightly different ways to wrap your operations with
retries.

## `retrying`

This is useful when you are not working in a monadic context.  You have an
operation that simply returns a value of some type `A`, and you want to retry
until it returns a value that you are happy with.

To use `retrying`, you pass in a predicate that decides whether you are
happy with the result or you want to retry.

The API looks like this:

```scala
def retrying[A](policy: RetryPolicy[Id],
                wasSuccessful: A => Boolean,
                onFailure: (A, RetryDetails) => Unit)
               (action: => A)
```

You need to pass in:

* a retry policy
* a predicate that decides whether the operation was successful
* a failure handler, often used for logging
* the operation that you want to wrap with retries

For example, let's keep rolling a die until we get a six.

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
val loadedDie = util.LoadedDie(2, 5, 4, 1, 3, 2, 6)

retrying(policy, predicate, onFailure){
  loadedDie.roll()
}
```

## `retryingM`

This is similar to `retrying`, but is useful when you are working in an
arbitrary `Monad` that is not a `MonadError`. Your operation doesn't throw
errors, but you want to retry until it returns a value that you are happy with.

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

For example, let's redo the rolling-a-six example, this time using `Future`.

```tut:book
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import cats.instances.future._

val futurePolicy = RetryPolicies.constantDelay[Future](10.milliseconds)

def futureOnFailure(failedValue: Int, details: RetryDetails): Future[Unit] = {
  Future(println(s"Rolled a $failedValue, retrying ..."))
}
```

```tut
import scala.concurrent.Await

val future = retryingM(futurePolicy, predicate, futureOnFailure){
  Future(loadedDie.roll())
}
Await.result(future, 1.minute)
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

## Syntactic sugar

Cats-retry include some syntactic sugar in order to reduce boilerplate.

```scala
import retry._
import cats.effect.IO
import retry.syntax.all._

implicit def noop[IO[_], A]: (A, RetryDetails) => IO[Unit] = retry.noop[IO, A]
implicit val policy: RetryPolicy[IO]                       = RetryPolicies.limitRetries[IO](2)

val httpClient = util.FlakyHttpClient()

val flakyRequest: IO[String] = IO(httpClient.getCatGif())

flakyRequest.retryingOnAllErrors
```
