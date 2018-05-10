---
layout: docs
title: Getting started
---

# Getting started

Let's start with a realistic example.

In order to provide business value to our stakeholders, we need to download a
textual description of a cat gif.

Unfortunately we have to do this over a flaky network connection, so there's an
70% chance it will fail.

We'll be working with the cats-effect `IO` monad, but any monad will do.

```tut:book
import cats.effect.IO
import scala.util.Random
import java.io.IOException

val flakyRequest: IO[String] = IO {
  if (Random.nextDouble() < 0.3) {
    "cute cat gets sleepy and falls asleep"
  } else {
    throw new IOException("Failed to download")
  }
}
```

To improve the chance of successfully downloading the file, let's wrap this with
some retry logic.

First we'll need a retry policy. Let's keep it simple: retry up to 10 times, with
no delay between attempts.

```tut:book
import retry._

val retryFiveTimes = RetryPolicies.limitRetries[IO](10)
```

We'll also provide an error handler that does some logging before every retry.
Note how this also happens within whatever monad you're working in, in this case
the `IO` monad.

```tut:book
import scala.concurrent.duration.FiniteDuration
import retry.RetryDetails._

def logError(err: Throwable, details: RetryDetails): IO[Unit] = details match {

  case WillDelayAndRetry(nextDelay: FiniteDuration,
                         retriesSoFar: Int,
                         cumulativeDelay: FiniteDuration) =>
    IO(println(s"Failed to download. So far we have retried $retriesSoFar times."))

  case GivingUp(totalRetries: Int, totalDelay: FiniteDuration) =>
    IO(println(s"Giving up after $totalRetries retries"))

}
```

Now we have a retry policy and an error handler, we can wrap our `IO` in
retries.

```tut:book
// This is needed to get an implicit cats.effect.Timer
import scala.concurrent.ExecutionContext.Implicits.global

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
```

Next steps:

* Learn about the other available [combinators](combinators.html)
* Learn more about [retry policies](policies.html)
* Learn about the [`Sleep` type class](sleep.html)
