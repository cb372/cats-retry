package retry

import cats.instances.all._
import cats.{Eq, Monoid, Id}
import cats.kernel.laws.discipline.BoundedSemilatticeTests
import org.scalacheck.{Arbitrary, Cogen, Gen}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.scalacheck.Checkers
import org.typelevel.discipline.scalatest.Discipline

import scala.concurrent.duration._
import cats.laws.discipline.ExhaustiveCheck
import cats.laws.discipline.eq.catsLawsEqForFn1Exhaustive

class RetryPolicyLawsSpec extends AnyFunSuite with Discipline with Checkers {

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

  test("meet associativity") {
    check((p1: RetryPolicy[Id], p2: RetryPolicy[Id], p3: RetryPolicy[Id]) =>
      Eq[RetryPolicy[Id]].eqv(p1.meet((p2).meet(p3)), (p1.meet(p2)).meet(p3)))
  }

  test("meet commutativity") {
    check((p1: RetryPolicy[Id], p2: RetryPolicy[Id]) =>
      Eq[RetryPolicy[Id]].eqv(p1.meet(p2), p2.meet(p1)))
  }

  test("meet idempotence") {
    check((p: RetryPolicy[Id]) => Eq[RetryPolicy[Id]].eqv(p.meet(p), p))
  }

  test("meet absorption") {
    check(
      (p: RetryPolicy[Id]) =>
        Eq[RetryPolicy[Id]].eqv(p.meet(Monoid[RetryPolicy[Id]].empty),
                                Monoid[RetryPolicy[Id]].empty))
  }

  test("join meet distributivity") {
    check(
      (p1: RetryPolicy[Id], p2: RetryPolicy[Id], p3: RetryPolicy[Id]) =>
        Eq[RetryPolicy[Id]].eqv(p1.meet(p2.join(p3)),
                                (p1.meet(p2)).join(p1.meet(p3))))
  }

  test("meet join distributivity") {
    check(
      (p1: RetryPolicy[Id], p2: RetryPolicy[Id], p3: RetryPolicy[Id]) =>
        Eq[RetryPolicy[Id]].eqv(p1.join(p2.meet(p3)),
                                (p1.join(p2)).meet(p1.join(p3))))
  }

  checkAll("BoundedSemilattice[RetryPolicy]",
           BoundedSemilatticeTests[RetryPolicy[Id]].boundedSemilattice)

}
