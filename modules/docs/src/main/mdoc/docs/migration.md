---
layout: docs
title: Migration Guide
---

# cats-retry v3 -> v4 Migration Guide

The major changes in v4 are as follows:
* **Scala 3**. The library is now published for Scala 3 only.
* **Cats Effect**. The library is now more strongly coupled to Cats Effect.
* **Adaption**. cats-retry now supports adaptation in the face of errors or failures.
* **Dynamic retry policies**. Retry policies are now more powerful.
* **API redesign**. The API has been completely rewritten to provide more power
and flexibility with fewer combinators.

The following migration guide will cover each of those topics.

## Scala 3

cats-retry v4 is only published for Scala 3.3.x. It will work with Scala 3.3.x
or newer.

It *might* work with Scala 2.13, but it has not been tested.

If you use Scala 2.13, please stick with cats-retry v3.

## Cats Effect

In v4 we decided to embrace Cats Effect more closely in order to simplify the
library. That means:
* the `cats-retry-alleycats` module is gone
* the `Sleep` type class is gone; we use CE `Temporal` directly
* Instead of an abstract error type `E`, the error type is now `Throwable`
* your effect monad will need to be Cats Effect `IO` or something similar
(anything with a `Temporal` instance)

If you use the `alleycats` module, please stick with cats-retry v3.

## Adaptation

The biggest new feature in v4 is support for adaptation.

Please see the [adaptation docs](./adaptation) for an explanation and a worked example.

## Dynamic retry policies

Retry policies now have access to the result of the action, so they can
dynamically change their behaviour depending on the particular failure or error
that occurred.

This means that `RetryPolicy` now has an extra type parameter. All the built-in
policies set this type param to `Any`.

Please see the [retry policies docs](./policies) for more details.

## API redesign

There are now fewer combinators, and hooks such as `wasSuccessful` or
`isWorthRetrying` have been replaced with a more powerful concept known as a
"result handler".

For each of the combinators in cats-retry v3, we will show how to update your
code when upgrading to v4.

### `retryingM`

This alias for `retryingOnFailures` was deprecated and has been removed.

Please follow the `retryingOnFailures` migration guide.

### `retryingOnFailures`

Old:

```scala
def retryingOnFailures[F[_]](
    policy: RetryPolicy[F],
    wasSuccessful: A => F[Boolean],
    onFailure: (A, RetryDetails) => F[Unit]
)(
    action: => F[A]
): F[A]
```

New:

```scala
def retryingOnFailures[F[_]: Temporal, A](
    action: F[A]
)(
    policy: RetryPolicy[F, A],
    valueHandler: ValueHandler[F, A]
): F[Either[A, A]]
```

The action is now passed as the first argument, not the last.

The policy can be used without any changes, assuming you have not implemented a
custom retry policy.

`wasSuccessful` and `onFailure` have been replaced by `valueHandler`. This is a
function that takes the action's result and the retry details, does any
necessary logging, and decides what to do next.

If your `wasSuccessful` is a simple predicate lifted to `F` using `pure`, then
there is a helper you can use to maintain the existing behaviour:

```scala
retryingOnFailures(action)(
  policy,
  valueHandler = ResultHandler.retryUntilSuccessful(predicate, log = doSomeLogging)
)
```

Note that `doSomeLogging` will be invoked after every attempt, not only on
failure, so it's not exactly the same as the old `onFailure` hook.

The result is now `F[Either[A, A]]`. This is to indicate whether the returned
value was a success or a failure. If the operation failed, but we gave up
because we exhausted our retries, then the result will be wrapped in a `Left`.
If it's a successful result, it will be a `Right`.

### `retryingOnSomeErrors`

Old:

```scala
def retryingOnSomeErrors[F[_], E](
    policy: RetryPolicy[F],
    isWorthRetrying: E => F[Boolean],
    onError: (E, RetryDetails) => F[Unit]
)(
    action: => F[A]
): F[A]
```

New:

```scala
def retryingOnErrors[F[_], A](
    action: F[A]
)(
    policy: RetryPolicy[F, Throwable],
    errorHandler: ErrorHandler[F, A]
): F[A]
```

The error type is now hardcoded to `Throwable`, so the `E` type parameter has
been removed.

The action is now passed as the first argument, not the last.

The policy can be used without any changes, assuming you have not implemented a
custom retry policy.

`isWorthRetrying` and `onError` have been replaced by `errorHandler`. This is a
function that takes the raised error and the retry details, does any necessary
logging, and decides what to do next.

If your `isWorthRetrying` is a simple predicate lifted to `F` using `pure`, then
there is a helper you can use to maintain the existing behaviour:

```scala
retryingOnErrors(action)(
  policy,
  errorHandler = ResultHandler.retryOnSomeErrors(predicate, log = doSomeLogging)
)
```

Note that `doSomeLogging` will be invoked every time an error is raised, not
only on errors that are worth retrying, so it's not exactly the same as the old
`onError` hook.

### `retryingOnAllErrors`

Old:

```scala
def retryingOnAllErrors[F[_], E](
    policy: RetryPolicy[F],
    onError: (E, RetryDetails) => F[Unit]
)(
    action: => F[A]
): F[A]
```

New:

```scala
def retryingOnErrors[F[_], A](
    action: F[A]
)(
    policy: RetryPolicy[F, Throwable],
    errorHandler: ErrorHandler[F, A]
): F[A]
```

The error type is now hardcoded to `Throwable`, so the `E` type parameter has
been removed.

The action is now passed as the first argument, not the last.

The policy can be used without any changes, assuming you have not implemented a
custom retry policy.

`onError` has been replaced by `errorHandler`. This is a function that takes the
raised error and the retry details, does any necessary logging, and decides what
to do next.

There is a helper you can use to maintain the existing behaviour:

```scala
retryingOnErrors(action)(
  policy,
  errorHandler = ResultHandler.retryOnAllErrors(log = doSomeLogging)
)
```

Note that `doSomeLogging` will be invoked every time an error is raised, so it
is basically the same as the old `onError` hook.

### `retryingOnFailuresAndSomeErrors`

Old:

```scala
def retryingOnFailuresAndSomeErrors[F[_], E](
    policy: RetryPolicy[F],
    wasSuccessful: A => F[Boolean],
    isWorthRetrying: E => F[Boolean],
    onFailure: (A, RetryDetails) => F[Unit],
    onError: (F, RetryDetails) => F[Unit]
)(
    action: => F[A]
): F[A]
```

New:

```scala
def retryingOnFailuresAndErrors[F[_], A](
    action: F[A]
)(
    policy: RetryPolicy[F, Either[Throwable, A]],
    errorOrValueHandler: ErrorOrValueHandler[F, A]
): F[Either[A, A]]
```

The error type is now hardcoded to `Throwable`, so the `E` type parameter has
been removed.

The action is now passed as the first argument, not the last.

The policy can be used without any changes, assuming you have not implemented a
custom retry policy.

`wasSuccessful`, `isWorthRetrying`, `onFailure` and `onError` have been replaced
by a single `errorOrValueHandler`. This is a function that takes the action's
result or the error that was raised, plus the retry details, does any necessary
logging, and decides what to do next.

See the [`retryingOnFailuresAndErrors`
docs](./combinators#retryingonfailuresanderrors) for more details on how to
write an `ErrorOrValueHandler`.

The result is now `F[Either[A, A]]`. This is to indicate whether the returned
value was a success or a failure. If the operation failed, but we gave up
because we exhausted our retries, then the result will be wrapped in a `Left`.
If it's a successful result, it will be a `Right`.

### `retryingOnFailuresAndAllErrors`

Old:

```scala
def retryingOnFailuresAndAllErrors[F[_], E](
    policy: RetryPolicy[F],
    wasSuccessful: A => F[Boolean],
    onFailure: (A, RetryDetails) => F[Unit],
    onError: (F, RetryDetails) => F[Unit]
)(
    action: => F[A]
): F[A]
```

New:

```scala
def retryingOnFailuresAndErrors[F[_], A](
    action: F[A]
)(
    policy: RetryPolicy[F, Either[Throwable, A]],
    errorOrValueHandler: ErrorOrValueHandler[F, A]
): F[Either[A, A]]
```

The error type is now hardcoded to `Throwable`, so the `E` type parameter has
been removed.

The action is now passed as the first argument, not the last.

The policy can be used without any changes, assuming you have not implemented a
custom retry policy.

`wasSuccessful`, `onFailure` and `onError` have been replaced by a single
`errorOrValueHandler`. This is a function that takes the action's result or the
error that was raised, plus the retry details, does any necessary logging, and
decides what to do next.

See the [`retryingOnFailuresAndErrors`
docs](./combinators#retryingonfailuresanderrors) for more details on how to
write an `ErrorOrValueHandler`.

The result is now `F[Either[A, A]]`. This is to indicate whether the returned
value was a success or a failure. If the operation failed, but we gave up
because we exhausted our retries, then the result will be wrapped in a `Left`.
If it's a successful result, it will be a `Right`.
