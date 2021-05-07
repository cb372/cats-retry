package retry.alleycats

import cats.instances.future._
import munit._
import retry._
import retry.alleycats.instances._

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration._
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

class PackageObjectLazinessSuite extends FunSuite {
  private val testContext =
    FunFixture[TestContext](_ => new TestContext, _ => ())

  // this test reproduces issue #116
  testContext.test(
    "retryingOnFailures should not evaluate the next attempt until it has finished sleeping"
  ) { ctx =>
    val policy = RetryPolicies.constantDelay[Future](5.second)

    // Kick off an operation which will fail, wait 5 seconds, and repeat forever, in a Future
    val _ = retryingOnFailures[String][Future](
      policy,
      _ => false,
      (a, retryDetails) => ctx.onError(a, retryDetails)
    ) {
      Future {
        ctx.attempts = ctx.attempts + 1
        ctx.attempts.toString
      }
    }

    // Wait for the first attempt to complete. By now we should be part way through the first delay.
    Thread.sleep(1000)

    // Assert that we haven't eagerly evaluated any more attempts.
    assertEquals(ctx.attempts, 1)
    assertEquals(ctx.errors.toList, List("1"))
    assertEquals(ctx.delays.toList, List(5.second))
  }

  private class TestContext {
    var attempts = 0
    val errors   = ArrayBuffer.empty[String]
    val delays   = ArrayBuffer.empty[FiniteDuration]
    var gaveUp   = false

    def onError(error: String, details: RetryDetails): Future[Unit] =
      Future.successful {
        errors.append(error)
        details match {
          case RetryDetails.WillDelayAndRetry(delay, _, _) =>
            delays.append(delay)
            ()
          case RetryDetails.GivingUp(_, _) => gaveUp = true
        }
      }
  }
}
