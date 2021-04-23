---
layout: docs
title: Sleep
---

# Sleep

You can configure how the delays between retries are implemented.

This is done using the `Sleep` type class:

```scala
trait Sleep[M[_]] {

  def sleep(delay: FiniteDuration): M[Unit]

}
```

Out of the box, the core module provides instances for any type with an implicit cats-effect
[`Temporal`](https://typelevel.org/cats-effect/datatypes/temporal.html) in scope.

For example using `cats.effect.IO`:

```scala mdoc:silent:reset-class
import retry.Sleep
import cats.effect.IO
import scala.concurrent.duration._
import cats.effect.unsafe.implicits.global

Sleep[IO].sleep(10.milliseconds)
```

Or if you're using an abstract `F[_]`:

```scala mdoc:silent:reset-class
import retry.Sleep
import cats.effect.Temporal
import scala.concurrent.duration._

def sleepWell[F[_]: Temporal] =
  Sleep[F].sleep(10.milliseconds)
```


Being able to inject your own `Sleep` instance can be handy in tests, as you
can mock it out to avoid slowing down your unit tests.


## alleycats-retry

The `alleycats-retry` module provides instances for `cats.Id`, `cats.Eval` and `Future` that
simply do a `Thread.sleep(...)`.

You can add it to your `build.sbt` as follows:
```scala mdoc:passthrough
println(
  s"""
  |```
  |libraryDependencies += "com.github.cb372" %% "alleycats-retry" % "${retry.BuildInfo.version.replaceFirst("\\+.*", "")}"
  |```
  |""".stripMargin.trim
)
```

To use them, simply import `retry.alleycats.instances._`:

```scala mdoc:silent:reset-class
import retry.Sleep
import retry.alleycats.instances._
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

Sleep[Future].sleep(10.milliseconds)
```

```scala mdoc:silent:reset-class
import retry.Sleep
import retry.alleycats.instances._
import cats.Id
import scala.concurrent.duration._

Sleep[Id].sleep(10.milliseconds)
```


Note: these instances are not provided if you are using Scala.js, as
`Thread.sleep` doesn't make any sense in JavaScript.

