package retry.mtl

import cats.data.EitherT
import cats.data.EitherT.catsDataMonadErrorFForEitherT
import munit._
import retry.syntax.all._
import retry.mtl.syntax.all._
import retry.{RetryDetails, RetryPolicies, RetryPolicy, Sleep}

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration._

class SyntaxSpec extends FunSuite {
  type ErrorOr[A] = Either[Throwable, A]
  type F[A]       = EitherT[ErrorOr, String, A]

  private val testContext =
    FunFixture[TestContext](_ => new TestContext, _ => ())

  testContext.test(
    "retryingOnSomeMtlErrors should retry until the action succeeds"
  ) { ctx =>
    implicit val sleepForEither: Sleep[F] = _ => EitherT.pure(())

    val error                  = new RuntimeException("Boom!")
    val policy: RetryPolicy[F] = RetryPolicies.constantDelay[F](1.second)

    def action: F[String] = {
      ctx.attempts = ctx.attempts + 1

      ctx.attempts match {
        case 1 => EitherT.leftT[ErrorOr, String]("one more time")
        case 2 => EitherT[ErrorOr, String, String](Left(error))
        case _ => EitherT.pure[ErrorOr, String]("yay")
      }
    }

    val finalResult: F[String] = action
      .retryingOnSomeErrors(_ == error, policy, ctx.onError)
      .retryingOnSomeMtlErrors[String](
        _ == "one more time",
        policy,
        ctx.onMtlError
      )

    assertEquals(finalResult.value, Right(Right("yay")))
    assertEquals(ctx.attempts, 3)
    assertEquals(ctx.errors.toList, List(Right("one more time"), Left(error)))
    assert(!ctx.gaveUp)
  }

  testContext.test(
    "retryingOnSomeMtlErrors should retry only if the error is worth retrying"
  ) { ctx =>
    implicit val sleepForEither: Sleep[F] = _ => EitherT.pure(())

    val error                  = new RuntimeException("Boom!")
    val policy: RetryPolicy[F] = RetryPolicies.constantDelay[F](1.second)

    def action: F[String] = {
      ctx.attempts = ctx.attempts + 1

      ctx.attempts match {
        case 1 => EitherT.leftT[ErrorOr, String]("one more time")
        case 2 => EitherT[ErrorOr, String, String](Left(error))
        case _ => EitherT.leftT[ErrorOr, String]("nope")
      }
    }

    val finalResult: F[String] = action
      .retryingOnSomeErrors(_ == error, policy, ctx.onError)
      .retryingOnSomeMtlErrors[String](
        _ == "one more time",
        policy,
        ctx.onMtlError
      )

    assertEquals(finalResult.value, Right(Left("nope")))
    assertEquals(ctx.attempts, 3)
    assertEquals(ctx.errors.toList, List(Right("one more time"), Left(error)))
    assert(
      !ctx.gaveUp
    ) // false because ctx.onError is only called when the error is worth retrying
  }

  testContext.test(
    "retryingOnSomeMtlErrors should retry until the policy chooses to give up"
  ) { ctx =>
    implicit val sleepForEither: Sleep[F] = _ => EitherT.pure(())

    val error                  = new RuntimeException("Boom!")
    val policy: RetryPolicy[F] = RetryPolicies.limitRetries[F](2)

    def action: F[String] = {
      ctx.attempts = ctx.attempts + 1

      ctx.attempts match {
        case 1 => EitherT.leftT[ErrorOr, String]("one more time")
        case 2 => EitherT[ErrorOr, String, String](Left(error))
        case _ => EitherT.leftT[ErrorOr, String]("one more time")
      }
    }

    val finalResult: F[String] = action
      .retryingOnSomeErrors(_ == error, policy, ctx.onError)
      .retryingOnSomeMtlErrors[String](
        _ == "one more time",
        policy,
        ctx.onMtlError
      )

    assertEquals(finalResult.value, Right(Left("one more time")))
    assertEquals(ctx.attempts, 4)
    assert(
      ctx.errors.toList == List(
        Right("one more time"),
        Left(error),
        Right("one more time"),
        Right("one more time")
      )
    )
    assert(ctx.gaveUp)
  }

  testContext.test(
    "retryingOnAllMtlErrors should retry until the action succeeds"
  ) { ctx =>
    implicit val sleepForEither: Sleep[F] = _ => EitherT.pure(())

    val error                  = new RuntimeException("Boom!")
    val policy: RetryPolicy[F] = RetryPolicies.constantDelay[F](1.second)

    def action: F[String] = {
      ctx.attempts = ctx.attempts + 1

      ctx.attempts match {
        case 1 => EitherT.leftT[ErrorOr, String]("one more time")
        case 2 => EitherT[ErrorOr, String, String](Left(error))
        case _ => EitherT.pure[ErrorOr, String]("yay")
      }
    }

    val finalResult: F[String] = action
      .retryingOnAllErrors(policy, ctx.onError)
      .retryingOnAllMtlErrors[String](policy, ctx.onMtlError)

    assertEquals(finalResult.value, Right(Right("yay")))
    assertEquals(ctx.attempts, 3)
    assertEquals(ctx.errors.toList, List(Right("one more time"), Left(error)))
    assert(!ctx.gaveUp)
  }

  testContext.test(
    "retryingOnAllMtlErrors should retry until the policy chooses to give up"
  ) { ctx =>
    implicit val sleepForEither: Sleep[F] = _ => EitherT.pure(())

    val error                  = new RuntimeException("Boom!")
    val policy: RetryPolicy[F] = RetryPolicies.limitRetries[F](2)

    def action: F[String] = {
      ctx.attempts = ctx.attempts + 1

      ctx.attempts match {
        case 1 => EitherT.leftT[ErrorOr, String]("one more time")
        case 2 => EitherT[ErrorOr, String, String](Left(error))
        case _ => EitherT.leftT[ErrorOr, String]("one more time")
      }
    }

    val finalResult: F[String] = action
      .retryingOnAllErrors(policy, ctx.onError)
      .retryingOnAllMtlErrors[String](policy, ctx.onMtlError)

    assertEquals(finalResult.value, Right(Left("one more time")))
    assertEquals(ctx.attempts, 4)
    assert(
      ctx.errors.toList == List(
        Right("one more time"),
        Left(error),
        Right("one more time"),
        Right("one more time")
      )
    )
    assert(ctx.gaveUp)
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
