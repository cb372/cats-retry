package retry

import java.util.concurrent.TimeUnit

import cats.Id
import cats.effect.IO
import cats.effect.std.Random
import cats.syntax.all.*
import org.scalacheck.{Arbitrary, Gen}
import org.scalacheck.Prop.forAll
import org.scalacheck.effect.PropF
import munit.{CatsEffectSuite, ScalaCheckSuite}
import retry.PolicyDecision.{DelayAndRetry, GiveUp}
import retry.RetryPolicies.*

import scala.concurrent.duration.*
import munit.Location

class RetryPoliciesSuite extends CatsEffectSuite with ScalaCheckSuite:

  given Arbitrary[RetryStatus] = Arbitrary {
    for
      a <- Gen.choose(0, 1000)
      b <- Gen.choose(0, 1000)
      c <- Gen.option(Gen.choose(b, 10000))
    yield RetryStatus(
      a,
      FiniteDuration(b.toLong, TimeUnit.MILLISECONDS),
      c.map(x => FiniteDuration(x.toLong, TimeUnit.MILLISECONDS))
    )
  }

  val genFiniteDuration: Gen[FiniteDuration] =
    Gen.posNum[Long].map(FiniteDuration(_, TimeUnit.NANOSECONDS))

  given (using Random[IO]): Arbitrary[RetryPolicy[IO, Any]] = Arbitrary {
    Gen.oneOf(
      Gen.const(alwaysGiveUp[IO]),
      genFiniteDuration.map(delay => constantDelay[IO](delay)),
      genFiniteDuration.map(baseDelay => exponentialBackoff[IO](baseDelay)),
      Gen.posNum[Int].map(maxRetries => limitRetries[IO](maxRetries)),
      genFiniteDuration.map(baseDelay => fibonacciBackoff[IO](baseDelay)),
      genFiniteDuration.map(baseDelay => fullJitter[IO](baseDelay))
    )
  }

  property("constantDelay - always retry with the same delay") {
    forAll((status: RetryStatus) =>
      assertEquals(
        constantDelay[Id](1.second).decideNextRetry((), status),
        PolicyDecision.DelayAndRetry(1.second)
      )
    )
  }

  test("exponentialBackoff - start with the base delay and double the delay after each iteration") {
    val policy                   = exponentialBackoff[Id](100.milliseconds)
    val arbitraryCumulativeDelay = 999.milliseconds
    val arbitraryPreviousDelay   = Some(999.milliseconds)

    def check(retriesSoFar: Int, expectedDelay: FiniteDuration) =
      val status = RetryStatus(
        retriesSoFar,
        arbitraryCumulativeDelay,
        arbitraryPreviousDelay
      )
      val verdict = policy.decideNextRetry((), status)
      assertEquals(verdict, PolicyDecision.DelayAndRetry(expectedDelay))

    check(0, 100.milliseconds)
    check(1, 200.milliseconds)
    check(2, 400.milliseconds)
    check(3, 800.milliseconds)
  }

  test("fibonacciBackoff - start with the base delay and increase the delay in a Fibonacci-y way") {
    val policy                   = fibonacciBackoff[Id](100.milliseconds)
    val arbitraryCumulativeDelay = 999.milliseconds
    val arbitraryPreviousDelay   = Some(999.milliseconds)

    def check(retriesSoFar: Int, expectedDelay: FiniteDuration) =
      val status = RetryStatus(
        retriesSoFar,
        arbitraryCumulativeDelay,
        arbitraryPreviousDelay
      )
      val verdict = policy.decideNextRetry((), status)
      assertEquals(verdict, PolicyDecision.DelayAndRetry(expectedDelay))

    check(0, 100.milliseconds)
    check(1, 100.milliseconds)
    check(2, 200.milliseconds)
    check(3, 300.milliseconds)
    check(4, 500.milliseconds)
    check(5, 800.milliseconds)
    check(6, 1300.milliseconds)
    check(7, 2100.milliseconds)
  }

  test("fullJitter - implement the AWS Full Jitter backoff algorithm") {
    val mkPolicy: IO[RetryPolicy[IO, Any]] = Random.scalaUtilRandom[IO].map { rnd =>
      given Random[IO] = rnd
      fullJitter[IO](100.milliseconds)
    }
    val arbitraryCumulativeDelay = 999.milliseconds
    val arbitraryPreviousDelay   = Some(999.milliseconds)

    case class TestCase(retriesSoFar: Int, expectedMaximumDelay: FiniteDuration)

    def check(testCase: TestCase): IO[Unit] =
      val status = RetryStatus(
        testCase.retriesSoFar,
        arbitraryCumulativeDelay,
        arbitraryPreviousDelay
      )
      (1 to 1000).toList.traverse_ { i =>
        for
          policy  <- mkPolicy
          verdict <- policy.decideNextRetry((), status)
        yield
          val delay = verdict.asInstanceOf[PolicyDecision.DelayAndRetry].delay
          assert(clue(delay) >= Duration.Zero)
          assert(clue(delay) < clue(testCase.expectedMaximumDelay))
      }

    val cases = List(
      TestCase(0, 100.milliseconds),
      TestCase(1, 200.milliseconds),
      TestCase(2, 400.milliseconds),
      TestCase(3, 800.milliseconds),
      TestCase(4, 1600.milliseconds),
      TestCase(5, 3200.milliseconds)
    )

    cases.traverse_(check)
  }

  test(
    "all built-in policies - never try to create a FiniteDuration of more than Long.MaxValue nanoseconds"
  ) {
    Random.scalaUtilRandom[IO].map { rnd =>
      given Random[IO] = rnd
      PropF.forAllF((policy: RetryPolicy[IO, Any], status: RetryStatus) =>
        policy.decideNextRetry((), status).map {
          case PolicyDecision.DelayAndRetry(nextDelay) =>
            assert(nextDelay.toNanos <= Long.MaxValue)
          case PolicyDecision.GiveUp =>
            assert(true)
        }
      )
    }
  }

  property("limitRetries - retry with no delay until the limit is reached") {
    forAll { (status: RetryStatus) =>
      val limit   = 500
      val verdict =
        limitRetries[Id](limit).decideNextRetry((), status)
      if status.retriesSoFar < limit then verdict == PolicyDecision.DelayAndRetry(Duration.Zero)
      else verdict == PolicyDecision.GiveUp
    }
  }

  property("capDelay - limits the maximum delay to the given duration") {
    forAll { (status: RetryStatus) =>
      assertEquals(
        capDelay(100.milliseconds, constantDelay[Id](101.milliseconds)).decideNextRetry((), status),
        DelayAndRetry(100.milliseconds)
      )
      assertEquals(
        capDelay(100.milliseconds, constantDelay[Id](99.milliseconds)).decideNextRetry((), status),
        DelayAndRetry(99.milliseconds)
      )
    }
  }

  test(
    "limitRetriesByDelay - give up if the underlying policy chooses a delay greater than the threshold"
  ) {
    forAll { (status: RetryStatus) =>
      assertEquals(
        limitRetriesByDelay(100.milliseconds, constantDelay[Id](101.milliseconds))
          .decideNextRetry((), status),
        GiveUp
      )
      assertEquals(
        limitRetriesByDelay(100.milliseconds, constantDelay[Id](99.milliseconds)).decideNextRetry((), status),
        DelayAndRetry(99.milliseconds)
      )
    }
  }

  test(
    "limitRetriesByCumulativeDelay - give up if cumulativeDelay + underlying policy's next delay >= threshold"
  ) {
    val cumulativeDelay        = 400.milliseconds
    val arbitraryRetriesSoFar  = 5
    val arbitraryPreviousDelay = Some(123.milliseconds)
    val status                 = RetryStatus(
      arbitraryRetriesSoFar,
      cumulativeDelay,
      arbitraryPreviousDelay
    )

    val threshold = 500.milliseconds

    def check(
        underlyingPolicy: RetryPolicy[Id, Any],
        expectedDecision: PolicyDecision
    ) =
      val policy = limitRetriesByCumulativeDelay(threshold, underlyingPolicy)
      assertEquals(policy.decideNextRetry((), status), expectedDecision)

    check(constantDelay(98.milliseconds), DelayAndRetry(98.milliseconds))
    check(constantDelay(99.milliseconds), DelayAndRetry(99.milliseconds))
    check(constantDelay(100.milliseconds), GiveUp)
    check(constantDelay(101.milliseconds), GiveUp)
  }

  test("dynamic - choose the appropriate policy based on the result of the last attempt") {
    val policy = dynamic[Id, Int] {
      case 42 => limitRetries(3)
      case _  => constantDelay(1.second)
    }

    // given the value 42, it should apply the "limit retries" policy
    assertEquals(
      policy.decideNextRetry(
        42,
        RetryStatus(
          retriesSoFar = 2,
          cumulativeDelay = 0.millis,
          previousDelay = Some(0.millis)
        )
      ),
      DelayAndRetry(0.millis)
    )
    assertEquals(
      policy.decideNextRetry(
        42,
        RetryStatus(
          retriesSoFar = 3,
          cumulativeDelay = 0.millis,
          previousDelay = Some(0.millis)
        )
      ),
      GiveUp
    )

    // given any other value, it should apply the "constant delay" policy
    assertEquals(
      policy.decideNextRetry(
        99,
        RetryStatus(
          retriesSoFar = 100,
          cumulativeDelay = 100.seconds,
          previousDelay = Some(1.second)
        )
      ),
      DelayAndRetry(1.second)
    )
  }
end RetryPoliciesSuite
