package retry

import retry.ScalaZIO._

import org.scalatest.flatspec.AnyFlatSpec
import zio.clock.Clock
import zio.duration.{Duration => ZDuration}
import zio.interop.catz._
import zio.test.mock.mockEnvironmentManaged
import zio.{DefaultRuntime, UIO, ZIO}

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration._

class ScalaZIOSpec extends AnyFlatSpec {

  behavior of "retryingM"

  it should "retry until the action succeeds" in new TestContext {
    val policy = RetryPolicies.constantDelay[ZIO[Clock, Nothing, ?]](10.second)

    val finalResult = retryingM[Int][ZIO[Clock, Nothing, ?]](
      policy,
      _.toInt > 3,
      onError
    ) {
      ZIO.effectTotal {
        attempts = attempts + 1
        attempts
      }
    }

    val runtime     = new DefaultRuntime {}
    val mockRuntime = mockEnvironmentManaged
    val f = runtime.unsafeRun {
      mockRuntime.use { env =>
        env.clock.sleep(ZDuration.fromScala(30 seconds)) *>
          finalResult.provide(env)
      }
    }

    assert(f == 4)
    assert(attempts == 4)
    assert(errors.toList == List(1, 2, 3))
    assert(delays.toList == List(10.second, 10.second, 10.second))
    assert(!gaveUp)

  }

  private class TestContext {

    var attempts = 0
    val errors   = ArrayBuffer.empty[Int]
    val delays   = ArrayBuffer.empty[FiniteDuration]
    var gaveUp   = false

    def onError(error: Int, details: RetryDetails): UIO[Unit] = {
      errors.append(error)
      details match {
        case RetryDetails.WillDelayAndRetry(delay, _, _) => delays.append(delay)
        case RetryDetails.GivingUp(_, _)                 => gaveUp = true
      }
      ZIO.unit
    }

  }
}
