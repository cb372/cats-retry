package retry.mtl

import cats.data.EitherT
import org.scalatest.flatspec.AnyFlatSpec
import retry.{RetryDetails, RetryPolicies, Sleep}

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration._

class PackageObjectSpec extends AnyFlatSpec {
  type ErrorOr[A] = Either[Throwable, A]
  type F[A]       = EitherT[ErrorOr, String, A]

  implicit val sleepForEitherT: Sleep[F] = _ => EitherT.pure(())

  behavior of "retryingOnSomeErrors"

  it should "retry until the action succeeds" in new TestContext {
    val policy = RetryPolicies.constantDelay[F](1.second)

    val isWorthRetrying: String => Boolean = _ == "one more time"

    val finalResult =
      retryingOnSomeErrors(policy, isWorthRetrying, onMtlError) {
        attempts = attempts + 1

        if (attempts < 3)
          EitherT.leftT[ErrorOr, String]("one more time")
        else
          EitherT.pure[ErrorOr, String]("yay")
      }

    assert(finalResult.value == Right(Right("yay")))
    assert(attempts == 3)
    assert(errors.toList == List("one more time", "one more time"))
    assert(!gaveUp)
  }

  it should "retry only if the error is worth retrying" in new TestContext {
    val policy = RetryPolicies.constantDelay[F](1.second)

    val isWorthRetrying: String => Boolean = _ == "one more time"

    val finalResult =
      retryingOnSomeErrors(policy, isWorthRetrying, onMtlError) {
        attempts = attempts + 1

        if (attempts < 3)
          EitherT.leftT[ErrorOr, String]("one more time")
        else
          EitherT.leftT[ErrorOr, String]("nope")
      }

    assert(finalResult.value == Right(Left("nope")))
    assert(attempts == 3)
    assert(errors.toList == List("one more time", "one more time"))
    assert(
      !gaveUp
    ) // false because onError is only called when the error is worth retrying
  }

  it should "retry until the policy chooses to give up" in new TestContext {
    val policy = RetryPolicies.limitRetries[F](2)

    val isWorthRetrying: String => Boolean = _ == "one more time"

    val finalResult =
      retryingOnSomeErrors(policy, isWorthRetrying, onMtlError) {
        attempts = attempts + 1
        EitherT.leftT[ErrorOr, String]("one more time")
      }

    assert(finalResult.value == Right(Left("one more time")))
    assert(attempts == 3)
    assert(
      errors.toList == List("one more time", "one more time", "one more time")
    )
    assert(gaveUp)
  }

  it should "retry in a stack-safe way" in new TestContext {
    val policy = RetryPolicies.limitRetries[F](10000)

    val isWorthRetrying: String => Boolean = _ == "one more time"

    val finalResult =
      retryingOnSomeErrors(policy, isWorthRetrying, onMtlError) {
        attempts = attempts + 1
        EitherT.leftT[ErrorOr, String]("one more time")
      }

    assert(finalResult.value == Right(Left("one more time")))
    assert(attempts == 10001)
    assert(gaveUp)
  }

  behavior of "retryingOnAllErrors"

  it should "retry until the action succeeds" in new TestContext {
    val policy = RetryPolicies.constantDelay[F](1.second)

    val finalResult = retryingOnAllErrors(policy, onMtlError) {
      attempts = attempts + 1

      if (attempts < 3)
        EitherT.leftT[ErrorOr, String]("one more time")
      else
        EitherT.pure[ErrorOr, String]("yay")
    }

    assert(finalResult.value == Right(Right("yay")))
    assert(attempts == 3)
    assert(errors.toList == List("one more time", "one more time"))
    assert(!gaveUp)
  }

  it should "retry until the policy chooses to give up" in new TestContext {
    val policy = RetryPolicies.limitRetries[F](2)

    val finalResult = retryingOnAllErrors(policy, onMtlError) {
      attempts = attempts + 1
      EitherT.leftT[ErrorOr, String]("one more time")
    }

    assert(finalResult.value == Right(Left("one more time")))
    assert(attempts == 3)
    assert(
      errors.toList == List("one more time", "one more time", "one more time")
    )
    assert(gaveUp)
  }

  it should "retry in a stack-safe way" in new TestContext {
    val policy = RetryPolicies.limitRetries[F](10000)

    val finalResult = retryingOnAllErrors(policy, onMtlError) {
      attempts = attempts + 1
      EitherT.leftT[ErrorOr, String]("one more time")
    }

    assert(finalResult.value == Right(Left("one more time")))
    assert(attempts == 10001)
    assert(gaveUp)
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
