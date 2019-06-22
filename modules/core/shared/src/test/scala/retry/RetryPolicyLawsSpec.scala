package retry

import cats.{Eq, Id}
import cats.instances.all._
import cats.kernel.laws.discipline.BoundedSemilatticeTests
import org.scalacheck.{Arbitrary, Cogen, Gen}
import org.scalatest.funsuite.AnyFunSuite
import org.typelevel.discipline.scalatest.Discipline

import scala.concurrent.duration._
import cats.laws.discipline.ExhaustiveCheck
import cats.laws.discipline.eq.catsLawsEqForFn1Exhaustive

class RetryPolicyLawsSpec extends AnyFunSuite with Discipline {

  implicit val cogenStatus: Cogen[RetryStatus] =
    Cogen { (seed, status) =>
      val a = Cogen[Int].perturb(seed, status.retriesSoFar)
      val b = Cogen[FiniteDuration].perturb(a, status.cumulativeDelay)
      Cogen[Option[FiniteDuration]].perturb(b, status.previousDelay)
    }

  implicit val arbitraryPolicyDecision: Arbitrary[PolicyDecision] =
    Arbitrary(for {
      delay <- Gen.choose(0, Long.MaxValue).map(Duration.fromNanos)
      decision <- Gen.oneOf(PolicyDecision.GiveUp,
                            PolicyDecision.DelayAndRetry(delay))
    } yield decision)

  implicit val arbRetryPolicy: Arbitrary[RetryPolicy[Id]] =
    Arbitrary(
      Arbitrary
        .arbitrary[RetryStatus => PolicyDecision]
        .map(RetryPolicy.apply[Id]))

  implicit val eqPolicyDecision: Eq[PolicyDecision] = Eq.by(_ match {
    case PolicyDecision.GiveUp           => None
    case PolicyDecision.DelayAndRetry(d) => Some(d)
  })

  implicit val retryStatusExhaustiveCheck: ExhaustiveCheck[RetryStatus] =
    ExhaustiveCheck.instance(
      Stream(
        RetryStatus.NoRetriesYet,
        RetryStatus(1, 10.millis, Some(10.millis)),
        RetryStatus(2, 20.millis, Some(10.millis)),
        RetryStatus(2, 30.millis, Some(20.millis)),
        RetryStatus(3, 70.millis, Some(40.millis)),
        RetryStatus(4, 150.millis, Some(80.millis))
      ))

  implicit val eqForRetryPolicy: Eq[RetryPolicy[Id]] =
    Eq.by(_.decideNextRetry)

  checkAll("BoundedSemilattice[RetryPolicy]",
           BoundedSemilatticeTests[RetryPolicy[Id]].boundedSemilattice)

}
