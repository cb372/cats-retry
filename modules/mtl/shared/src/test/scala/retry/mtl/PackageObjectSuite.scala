package retry.mtl

import cats.data.EitherT
import munit.FunSuite
import retry.{RetryDetails, RetryPolicies, Sleep}

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration.*

class PackageObjectSuite extends FunSuite:
  type ErrorOr[A] = Either[Throwable, A]
  type F[A]       = EitherT[ErrorOr, String, A]

  given Sleep[F] = _ => EitherT.pure(())

  private class TestContext:
    var attempts = 0
    val errors   = ArrayBuffer.empty[String]
    val delays   = ArrayBuffer.empty[FiniteDuration]
    var gaveUp   = false

    def onMtlError(
        error: String,
        details: RetryDetails
    ): F[Unit] =
      errors.append(error)
      details match
        case RetryDetails.WillDelayAndRetry(delay, _, _) => delays.append(delay)
        case RetryDetails.GivingUp(_, _)                 => gaveUp = true
      EitherT.pure(())

  private val fixture = FunFixture[TestContext](
    setup = _ => new TestContext,
    teardown = _ => ()
  )

  fixture.test("retryingOnSomeErrors - retry until the action succeeds") { context =>
    import context.*

    val policy = RetryPolicies.constantDelay[F](1.second)

    val isWorthRetrying: String => F[Boolean] =
      s => EitherT.pure(s == "one more time")

    val finalResult =
      retryingOnSomeErrors(policy, isWorthRetrying, onMtlError) {
        attempts = attempts + 1

        if attempts < 3 then EitherT.leftT[ErrorOr, String]("one more time")
        else EitherT.pure[ErrorOr, String]("yay")
      }

    assertEquals(finalResult.value, Right(Right("yay")))
    assertEquals(attempts, 3)
    assertEquals(errors.toList, List("one more time", "one more time"))
    assertEquals(gaveUp, false)
  }

  fixture.test("retryingOnSomeErrors - retry only if the error is worth retrying") { context =>
    import context.*

    val policy = RetryPolicies.constantDelay[F](1.second)

    val isWorthRetrying: String => F[Boolean] =
      s => EitherT.pure(s == "one more time")

    val finalResult =
      retryingOnSomeErrors(policy, isWorthRetrying, onMtlError) {
        attempts = attempts + 1

        if attempts < 3 then EitherT.leftT[ErrorOr, String]("one more time")
        else EitherT.leftT[ErrorOr, String]("nope")
      }

    assertEquals(finalResult.value, Right(Left("nope")))
    assertEquals(attempts, 3)
    assertEquals(errors.toList, List("one more time", "one more time"))
    assertEquals(
      gaveUp,
      false // false because onError is only called when the error is worth retrying
    )
  }

  fixture.test("retryingOnSomeErrors - retry until the policy chooses to give up") { context =>
    import context.*

    val policy = RetryPolicies.limitRetries[F](2)

    val isWorthRetrying: String => F[Boolean] =
      s => EitherT.pure(s == "one more time")

    val finalResult =
      retryingOnSomeErrors(policy, isWorthRetrying, onMtlError) {
        attempts = attempts + 1
        EitherT.leftT[ErrorOr, String]("one more time")
      }

    assertEquals(finalResult.value, Right(Left("one more time")))
    assertEquals(attempts, 3)
    assertEquals(
      errors.toList,
      List("one more time", "one more time", "one more time")
    )
    assertEquals(gaveUp, true)
  }

  fixture.test("retryingOnSomeErrors - retry in a stack-safe way") { context =>
    import context.*

    val policy = RetryPolicies.limitRetries[F](10000)

    val isWorthRetrying: String => F[Boolean] =
      s => EitherT.pure(s == "one more time")

    val finalResult =
      retryingOnSomeErrors(policy, isWorthRetrying, onMtlError) {
        attempts = attempts + 1
        EitherT.leftT[ErrorOr, String]("one more time")
      }

    assertEquals(finalResult.value, Right(Left("one more time")))
    assertEquals(attempts, 10001)
    assertEquals(gaveUp, true)
  }

  fixture.test("retryingOnAllErrors - retry until the action succeeds") { context =>
    import context.*

    val policy = RetryPolicies.constantDelay[F](1.second)

    val finalResult = retryingOnAllErrors(policy, onMtlError) {
      attempts = attempts + 1

      if attempts < 3 then EitherT.leftT[ErrorOr, String]("one more time")
      else EitherT.pure[ErrorOr, String]("yay")
    }

    assertEquals(finalResult.value, Right(Right("yay")))
    assertEquals(attempts, 3)
    assertEquals(errors.toList, List("one more time", "one more time"))
    assertEquals(gaveUp, false)
  }

  fixture.test("retryingOnAllErrors - retry until the policy chooses to give up") { context =>
    import context.*

    val policy = RetryPolicies.limitRetries[F](2)

    val finalResult = retryingOnAllErrors(policy, onMtlError) {
      attempts = attempts + 1
      EitherT.leftT[ErrorOr, String]("one more time")
    }

    assertEquals(finalResult.value, Right(Left("one more time")))
    assertEquals(attempts, 3)
    assertEquals(
      errors.toList,
      List("one more time", "one more time", "one more time")
    )
    assertEquals(gaveUp, true)
  }

  fixture.test("retryingOnAllErrors - retry in a stack-safe way") { context =>
    import context.*

    val policy = RetryPolicies.limitRetries[F](10000)

    val finalResult = retryingOnAllErrors(policy, onMtlError) {
      attempts = attempts + 1
      EitherT.leftT[ErrorOr, String]("one more time")
    }

    assertEquals(finalResult.value, Right(Left("one more time")))
    assertEquals(attempts, 10001)
    assertEquals(gaveUp, true)
  }
end PackageObjectSuite
