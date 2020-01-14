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

```scala mdoc
import cats.effect.IO

val httpClient = util.FlakyHttpClient()

val flakyRequest: IO[String] = IO {
  httpClient.getCatGif()
}
```

To improve the chance of successfully downloading the file, let's wrap this with
some retry logic.

We'll add dependencies on the `core` and `cats-effect` modules:

```scala mdoc:passthrough
println(
  s"""
  |```
  |val catsRetryVersion = "${retry.BuildInfo.version.replaceFirst("\\+.*", "")}"
  |libraryDependencies += "com.github.cb372" %% "cats-retry" % catsRetryVersion,
  |```
  |""".stripMargin.trim
)
```

(Note: if you're using Scala.js, you'll need a `%%%` instead of `%%`.)

First we'll need a retry policy. We'll keep it simple: retry up to 5 times, with
no delay between attempts. (See the [retry policies page](policies.html) for
information on more powerful policies).

```scala mdoc
import retry._

val retryFiveTimes = RetryPolicies.limitRetries[IO](5)
```

We'll also provide an error handler that does some logging before every retry.
Note how this also happens within whatever monad you're working in, in this case
the `IO` monad.

```scala mdoc:reset-class
import cats.effect.IO
import scala.concurrent.duration.FiniteDuration
import retry._
import retry.RetryDetails._

val httpClient = util.FlakyHttpClient()

val flakyRequest: IO[String] = IO {
  httpClient.getCatGif()
}

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

// Now we have a retry policy and an error handler, we can wrap our `IO` inretries.

// We need an implicit cats.effect.Timer
import cats.effect.Timer
import scala.concurrent.ExecutionContext.global
implicit val timer: Timer[IO] = IO.timer(global)


val flakyRequestWithRetry: IO[String] =
  retryingOnAllErrors[String](
    policy = RetryPolicies.limitRetries[IO](5),
    onError = logError
  )(flakyRequest)

// Let's see it in action.

flakyRequestWithRetry.unsafeRunSync()

logMessages.foreach(println)
```

Next steps:

* Learn about the other available [combinators](combinators.html)
* Learn about the [MTL combinators](mtl-combinators.html)
* Learn more about [retry policies](policies.html)
* Learn about the [`Sleep` type class](sleep.html)
