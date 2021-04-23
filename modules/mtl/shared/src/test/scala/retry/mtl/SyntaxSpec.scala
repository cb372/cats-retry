package retry.mtl

import cats.data.EitherT
import cats.data.EitherT.catsDataMonadErrorFForEitherT
import org.scalatest.flatspec.AnyFlatSpec
import retry.syntax.all._
import retry.mtl.syntax.all._
import retry.{RetryDetails, RetryPolicies, RetryPolicy, Sleep}

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration._

class SyntaxSpec extends AnyFlatSpec {
  type ErrorOr[A] = Either[Throwable, A]
  type F[A]       = EitherT[ErrorOr, String, A]

  behavior of "retryingOnSomeMtlErrors"

  it should "retry until the action succeeds" in new TestContext {
    implicit val sleepForEither: Sleep[F] = _ => EitherT.pure(())

    val error                  = new RuntimeException("Boom!")
    val policy: RetryPolicy[F] = RetryPolicies.constantDelay[F](1.second)

    def action: F[String] = {
      attempts = attempts + 1

      attempts match {
        case 1 => EitherT.leftT[ErrorOr, String]("one more time")
        case 2 => EitherT[ErrorOr, String, String](Left(error))
        case _ => EitherT.pure[ErrorOr, String]("yay")
      }
    }

    val finalResult: F[String] = action
      .retryingOnSomeErrors(s => EitherT.pure(s == error), policy, onError)
      .retryingOnSomeMtlErrors[String](
        s => EitherT.pure(s == "one more time"),
        policy,
        onMtlError
      )

    assert(finalResult.value == Right(Right("yay")))
    assert(attempts == 3)
    assert(errors.toList == List(Right("one more time"), Left(error)))
    assert(!gaveUp)
  }

  it should "retry only if the error is worth retrying" in new TestContext {
    implicit val sleepForEither: Sleep[F] = _ => EitherT.pure(())

    val error                  = new RuntimeException("Boom!")
    val policy: RetryPolicy[F] = RetryPolicies.constantDelay[F](1.second)

    def action: F[String] = {
      attempts = attempts + 1

      attempts match {
        case 1 => EitherT.leftT[ErrorOr, String]("one more time")
        case 2 => EitherT[ErrorOr, String, String](Left(error))
        case _ => EitherT.leftT[ErrorOr, String]("nope")
      }
    }

    val finalResult: F[String] = action
      .retryingOnSomeErrors(s => EitherT.pure(s == error), policy, onError)
      .retryingOnSomeMtlErrors[String](
        s => EitherT.pure(s == "one more time"),
        policy,
        onMtlError
      )

    assert(finalResult.value == Right(Left("nope")))
    assert(attempts == 3)
    assert(errors.toList == List(Right("one more time"), Left(error)))
    assert(
      !gaveUp
    ) // false because onError is only called when the error is worth retrying
  }

  it should "retry until the policy chooses to give up" in new TestContext {
    implicit val sleepForEither: Sleep[F] = _ => EitherT.pure(())

    val error                  = new RuntimeException("Boom!")
    val policy: RetryPolicy[F] = RetryPolicies.limitRetries[F](2)

    def action: F[String] = {
      attempts = attempts + 1

      attempts match {
        case 1 => EitherT.leftT[ErrorOr, String]("one more time")
        case 2 => EitherT[ErrorOr, String, String](Left(error))
        case _ => EitherT.leftT[ErrorOr, String]("one more time")
      }
    }

    val finalResult: F[String] = action
      .retryingOnSomeErrors(s => EitherT.pure(s == error), policy, onError)
      .retryingOnSomeMtlErrors[String](
        s => EitherT.pure(s == "one more time"),
        policy,
        onMtlError
      )

    assert(finalResult.value == Right(Left("one more time")))
    assert(attempts == 4)
    assert(
      errors.toList == List(
        Right("one more time"),
        Left(error),
        Right("one more time"),
        Right("one more time")
      )
    )
    assert(gaveUp)
  }

  behavior of "retryingOnAllMtlErrors"

  it should "retry until the action succeeds" in new TestContext {
    implicit val sleepForEither: Sleep[F] = _ => EitherT.pure(())

    val error                  = new RuntimeException("Boom!")
    val policy: RetryPolicy[F] = RetryPolicies.constantDelay[F](1.second)

    def action: F[String] = {
      attempts = attempts + 1

      attempts match {
        case 1 => EitherT.leftT[ErrorOr, String]("one more time")
        case 2 => EitherT[ErrorOr, String, String](Left(error))
        case _ => EitherT.pure[ErrorOr, String]("yay")
      }
    }

    val finalResult: F[String] = action
      .retryingOnAllErrors(policy, onError)
      .retryingOnAllMtlErrors[String](policy, onMtlError)

    assert(finalResult.value == Right(Right("yay")))
    assert(attempts == 3)
    assert(errors.toList == List(Right("one more time"), Left(error)))
    assert(!gaveUp)
  }

  it should "retry until the policy chooses to give up" in new TestContext {
    implicit val sleepForEither: Sleep[F] = _ => EitherT.pure(())

    val error                  = new RuntimeException("Boom!")
    val policy: RetryPolicy[F] = RetryPolicies.limitRetries[F](2)

    def action: F[String] = {
      attempts = attempts + 1

      attempts match {
        case 1 => EitherT.leftT[ErrorOr, String]("one more time")
        case 2 => EitherT[ErrorOr, String, String](Left(error))
        case _ => EitherT.leftT[ErrorOr, String]("one more time")
      }
    }

    val finalResult: F[String] = action
      .retryingOnAllErrors(policy, onError)
      .retryingOnAllMtlErrors[String](policy, onMtlError)

    assert(finalResult.value == Right(Left("one more time")))
    assert(attempts == 4)
    assert(
      errors.toList == List(
        Right("one more time"),
        Left(error),
        Right("one more time"),
        Right("one more time")
      )
    )
    assert(gaveUp)
  }

  private class TestContext {
    var attempts = 0
    val errors   = ArrayBuffer.empty[Either[Throwable, String]]
    val delays   = ArrayBuffer.empty[FiniteDuration]
    var gaveUp   = false

    def onError(error: Throwable, details: RetryDetails): F[Unit] =
      onErrorInternal(Left(error), details)

    def onMtlError(error: String, details: RetryDetails): F[Unit] =
      onErrorInternal(Right(error), details)

    private def onErrorInternal(
        error: Either[Throwable, String],
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
