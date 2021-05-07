package retry.mtl

import cats.data.EitherT
import munit._
import retry.{RetryDetails, RetryPolicies, Sleep}

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration._

class PackageObjectSpec extends FunSuite {
  type ErrorOr[A] = Either[Throwable, A]
  type F[A]       = EitherT[ErrorOr, String, A]

  implicit val sleepForEitherT: Sleep[F] = _ => EitherT.pure(())

  private val testContext =
    FunFixture[TestContext](_ => new TestContext, _ => ())

  testContext.test(
    "retryingOnSomectx.errors should retry until the action succeeds"
  ) { ctx =>
    val policy = RetryPolicies.constantDelay[F](1.second)

    val isWorthRetrying: String => Boolean = _ == "one more time"

    val finalResult =
      retryingOnSomeErrors(policy, isWorthRetrying, ctx.onMtlError) {
        ctx.attempts = ctx.attempts + 1

        if (ctx.attempts < 3)
          EitherT.leftT[ErrorOr, String]("one more time")
        else
          EitherT.pure[ErrorOr, String]("yay")
      }

    assertEquals(finalResult.value, Right(Right("yay")))
    assertEquals(ctx.attempts, 3)
    assertEquals(ctx.errors.toList, List("one more time", "one more time"))
    assert(!ctx.gaveUp)
  }

  testContext.test(
    "retryingOnSomectx.errors should retry only if the error is worth retrying"
  ) { ctx =>
    val policy = RetryPolicies.constantDelay[F](1.second)

    val isWorthRetrying: String => Boolean = _ == "one more time"

    val finalResult =
      retryingOnSomeErrors(policy, isWorthRetrying, ctx.onMtlError) {
        ctx.attempts = ctx.attempts + 1

        if (ctx.attempts < 3)
          EitherT.leftT[ErrorOr, String]("one more time")
        else
          EitherT.leftT[ErrorOr, String]("nope")
      }

    assertEquals(finalResult.value, Right(Left("nope")))
    assertEquals(ctx.attempts, 3)
    assertEquals(ctx.errors.toList, List("one more time", "one more time"))
    assert(
      !ctx.gaveUp
    ) // false because onError is only called when the error is worth retrying
  }

  testContext.test(
    "retryingOnSomectx.errors should retry until the policy chooses to give up"
  ) { ctx =>
    val policy = RetryPolicies.limitRetries[F](2)

    val isWorthRetrying: String => Boolean = _ == "one more time"

    val finalResult =
      retryingOnSomeErrors(policy, isWorthRetrying, ctx.onMtlError) {
        ctx.attempts = ctx.attempts + 1
        EitherT.leftT[ErrorOr, String]("one more time")
      }

    assertEquals(finalResult.value, Right(Left("one more time")))
    assertEquals(ctx.attempts, 3)
    assert(
      ctx.errors.toList == List(
        "one more time",
        "one more time",
        "one more time"
      )
    )
    assert(ctx.gaveUp)
  }

  testContext.test(
    "retryingOnSomectx.errors should retry in a stack-safe way"
  ) { ctx =>
    val policy = RetryPolicies.limitRetries[F](10000)

    val isWorthRetrying: String => Boolean = _ == "one more time"

    val finalResult =
      retryingOnSomeErrors(policy, isWorthRetrying, ctx.onMtlError) {
        ctx.attempts = ctx.attempts + 1
        EitherT.leftT[ErrorOr, String]("one more time")
      }

    assertEquals(finalResult.value, Right(Left("one more time")))
    assertEquals(ctx.attempts, 10001)
    assert(ctx.gaveUp)
  }

  testContext.test(
    "retryingOnAllctx.errors should retry until the action succeeds"
  ) { ctx =>
    val policy = RetryPolicies.constantDelay[F](1.second)

    val finalResult = retryingOnAllErrors(policy, ctx.onMtlError) {
      ctx.attempts = ctx.attempts + 1

      if (ctx.attempts < 3)
        EitherT.leftT[ErrorOr, String]("one more time")
      else
        EitherT.pure[ErrorOr, String]("yay")
    }

    assertEquals(finalResult.value, Right(Right("yay")))
    assertEquals(ctx.attempts, 3)
    assertEquals(ctx.errors.toList, List("one more time", "one more time"))
    assert(!ctx.gaveUp)
  }

  testContext.test(
    "retryingOnAllctx.errors should retry until the policy chooses to give up"
  ) { ctx =>
    val policy = RetryPolicies.limitRetries[F](2)

    val finalResult = retryingOnAllErrors(policy, ctx.onMtlError) {
      ctx.attempts = ctx.attempts + 1
      EitherT.leftT[ErrorOr, String]("one more time")
    }

    assertEquals(finalResult.value, Right(Left("one more time")))
    assertEquals(ctx.attempts, 3)
    assert(
      ctx.errors.toList == List(
        "one more time",
        "one more time",
        "one more time"
      )
    )
    assert(ctx.gaveUp)
  }

  testContext.test("retryingOnAllctx.errors should retry in a stack-safe way") {
    ctx =>
      val policy = RetryPolicies.limitRetries[F](10000)

      val finalResult = retryingOnAllErrors(policy, ctx.onMtlError) {
        ctx.attempts = ctx.attempts + 1
        EitherT.leftT[ErrorOr, String]("one more time")
      }

      assertEquals(finalResult.value, Right(Left("one more time")))
      assertEquals(ctx.attempts, 10001)
      assert(ctx.gaveUp)
  }

  private class TestContext {
    var attempts = 0
    val errors   = ArrayBuffer.empty[String]
    val delays   = ArrayBuffer.empty[FiniteDuration]
    var gaveUp   = false

    def onMtlError(
        error: String,
        details: RetryDetails
    ): F[Unit] = {
      errors.append(error)
      details match {
        case RetryDetails.WillDelayAndRetry(delay, _, _) => delays.append(delay)
        case RetryDetails.GivingUp(_, _)                 => gaveUp = true
      }
      EitherT.pure(())
    }
  }
}
