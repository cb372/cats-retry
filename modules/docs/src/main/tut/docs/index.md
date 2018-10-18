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

```tut:book
import cats.effect.IO

val httpClient = util.FlakyHttpClient()

val flakyRequest: IO[String] = IO {
  httpClient.getCatGif()
}
```

To improve the chance of successfully downloading the file, let's wrap this with
some retry logic.

We'll add dependencies on the `core` and `cats-effect` modules:

```
val catsRetryVersion = "0.2.0"
libraryDependencies ++= Seq(
  "com.github.cb372" %% "cats-retry-core"        % catsRetryVersion,
  "com.github.cb372" %% "cats-retry-cats-effect" % catsRetryVersion
)
```

First we'll need a retry policy. We'll keep it simple: retry up to 5 times, with
no delay between attempts. (See the [retry policies page](policies.html) for
information on more powerful policies).

```tut:book
import retry._

val retryFiveTimes = RetryPolicies.limitRetries[IO](5)
```

We'll also provide an error handler that does some logging before every retry.
Note how this also happens within whatever monad you're working in, in this case
the `IO` monad.

```tut:book
import scala.concurrent.duration.FiniteDuration
import retry.RetryDetails._

val logMessages = collection.mutable.ArrayBuffer.empty[String]

def logError(err: Throwable, details: RetryDetails): IO[Unit] = details match {

  case WillDelayAndRetry(nextDelay: FiniteDuration,
                         retriesSoFar: Int,
                         cumulativeDelay: FiniteDuration) =>
    IO {
      logMessages.append(
        s"Failed to download. So far we have retried $retriesSoFar times.")
    }

  case GivingUp(totalRetries: Int, totalDelay: FiniteDuration) =>
    IO {
      logMessages.append(s"Giving up after $totalRetries retries")
    }

}
```

Now we have a retry policy and an error handler, we can wrap our `IO` in
retries.

```tut:book
// We need an implicit cats.effect.Timer
import cats.effect.Timer
import scala.concurrent.ExecutionContext.global
implicit val timer: Timer[IO] = IO.timer(global)

// This is so we can use that Timer to perform delays between retries
import retry.CatsEffect._

val flakyRequestWithRetry: IO[String] =
  retryingOnAllErrors[String](
    policy = retryFiveTimes,
    onError = logError
  )(flakyRequest)
```

Let's see it in action.

```tut
flakyRequestWithRetry.unsafeRunSync()

logMessages.foreach(println)
```

Next steps:

* Learn about the other available [combinators](combinators.html)
* Learn more about [retry policies](policies.html)
* Learn about the [`Sleep` type class](sleep.html)
