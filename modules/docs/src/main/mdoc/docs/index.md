---
layout: docs
title: Getting started
---

# Getting started

Let's start with a realistic example.

In order to provide business value to our stakeholders, we need to download a
textual description of a cat gif.

Unfortunately we have to do this over a flaky network connection, so there's a
high probability it will fail.

We'll be working with the cats-effect `IO` monad, but any monad will do.

```scala mdoc:silent
import cats.effect.IO

val httpClient = util.FlakyHttpClient()

val flakyRequest: IO[String] = httpClient.getCatGif
```

To improve the chance of successfully downloading the file, let's wrap this with
some retry logic.

We'll add a dependency on the `core` module:

````scala mdoc:passthrough
println(
  s"""
  |```
  |val catsRetryVersion = "${retry.BuildInfo.version.replaceFirst("\\+.*", "")}"
  |libraryDependencies += "com.github.cb372" %% "cats-retry" % catsRetryVersion,
  |```
  |""".stripMargin.trim
)
````

(Note: if you're using Scala.js, you'll need a `%%%` instead of `%%`.)

First we'll need a retry policy. We'll keep it simple: retry up to 5 times, with
no delay between attempts. (See the [retry policies page](policies.html) for
information on more powerful policies).

```scala mdoc:silent
import retry._

val retryFiveTimes = RetryPolicies.limitRetries[IO](5)
```

We can also provide an error handler to do some logging each time the operation
raises an error.

Note how this also happens within whatever monad you're working in, in this case
the `IO` monad.

```scala mdoc:silent
import scala.concurrent.duration.FiniteDuration
import retry.RetryDetails.NextStep._

val logMessages = collection.mutable.ArrayBuffer.empty[String]

def logError(err: Throwable, details: RetryDetails): IO[Unit] =
  details.nextStepIfUnsuccessful match
    case DelayAndRetry(nextDelay: FiniteDuration) =>
      IO(logMessages.append(s"Failed to download. So far we have retried ${details.retriesSoFar} times."))
    case GiveUp =>
      IO(logMessages.append(s"Giving up after ${details.retriesSoFar} retries"))
```

Now we have a retry policy and an error handler, we can wrap our `IO` in retries.

```scala mdoc:silent
import retry.ResultHandler.retryOnAllErrors

val flakyRequestWithRetry: IO[String] =
  retryingOnErrors(
    policy = retryFiveTimes,
    errorHandler = retryOnAllErrors(logError)
  )(flakyRequest)
```

Let's see it in action.

```scala mdoc
import cats.effect.unsafe.implicits.global

flakyRequestWithRetry.unsafeRunSync()

logMessages.foreach(println)
```

Next steps:

- Learn about the other available [combinators](combinators.html)
- Learn about the [MTL combinators](mtl-combinators.html)
- Learn more about [retry policies](policies.html)
