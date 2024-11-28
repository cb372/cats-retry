package retry

import cats.instances.all.*
import cats.{Eq, Monoid, Id}
import cats.kernel.laws.discipline.BoundedSemilatticeTests
import munit.DisciplineSuite
import org.scalacheck.{Arbitrary, Cogen, Gen}
import org.scalacheck.Prop.*

import scala.concurrent.duration.*
import cats.laws.discipline.ExhaustiveCheck
import cats.laws.discipline.eq.catsLawsEqForFn1Exhaustive
import cats.arrow.FunctionK
import cats.Monad

class RetryPolicyLawsSuite extends DisciplineSuite:

  given Cogen[RetryStatus] =
    Cogen { (seed, status) =>
      val a = Cogen[Int].perturb(seed, status.retriesSoFar)
      val b = Cogen[FiniteDuration].perturb(a, status.cumulativeDelay)
      Cogen[Option[FiniteDuration]].perturb(b, status.previousDelay)
    }

  given Arbitrary[PolicyDecision] =
    Arbitrary(for
      delay <- Gen.choose(0L, Long.MaxValue).map(Duration.fromNanos)
      decision <- Gen
        .oneOf(PolicyDecision.GiveUp, PolicyDecision.DelayAndRetry(delay))
    yield decision)

  given Arbitrary[RetryPolicy[Id]] =
    Arbitrary(
      Arbitrary
        .arbitrary[RetryStatus => PolicyDecision]
        .map(RetryPolicy.apply[Id])
    )

  given Eq[PolicyDecision] = Eq.by {
    case PolicyDecision.GiveUp => None
    case PolicyDecision.DelayAndRetry(d) => Some(d)
  }

  given ExhaustiveCheck[RetryStatus] =
    ExhaustiveCheck.instance(
      List(
        RetryStatus.NoRetriesYet,
        RetryStatus(1, 10.millis, Some(10.millis)),
        RetryStatus(2, 20.millis, Some(10.millis)),
        RetryStatus(2, 30.millis, Some(20.millis)),
        RetryStatus(3, 70.millis, Some(40.millis)),
        RetryStatus(4, 150.millis, Some(80.millis)),
        RetryStatus(5, Long.MaxValue.nanos, Some(100.millis))
      )
    )

  given Eq[RetryPolicy[Id]] =
    Eq.by(_.decideNextRetry)

  property("meet associativity") {
    forAll((p1: RetryPolicy[Id], p2: RetryPolicy[Id], p3: RetryPolicy[Id]) =>
      Eq[RetryPolicy[Id]].eqv(p1.meet((p2).meet(p3)), (p1.meet(p2)).meet(p3))
    )
  }

  property("meet commutativity") {
    forAll((p1: RetryPolicy[Id], p2: RetryPolicy[Id]) => Eq[RetryPolicy[Id]].eqv(p1.meet(p2), p2.meet(p1)))
  }

  property("meet idempotence") {
    forAll((p: RetryPolicy[Id]) => Eq[RetryPolicy[Id]].eqv(p.meet(p), p))
  }

  property("meet identity") {
    forAll((p: RetryPolicy[Id]) => Eq[RetryPolicy[Id]].eqv(p.meet(RetryPolicies.alwaysGiveUp[Id]), p))
  }

  property("join meet absorption") {
    forAll((p1: RetryPolicy[Id], p2: RetryPolicy[Id]) => Eq[RetryPolicy[Id]].eqv(p1.meet(p1.join(p2)), p1))
  }

  property("meet join absorption") {
    forAll((p1: RetryPolicy[Id], p2: RetryPolicy[Id]) => Eq[RetryPolicy[Id]].eqv(p1.join(p1.meet(p2)), p1))
  }

  property("meet absorption") {
    forAll((p: RetryPolicy[Id]) =>
      Eq[RetryPolicy[Id]].eqv(
        p.meet(Monoid[RetryPolicy[Id]].empty),
        Monoid[RetryPolicy[Id]].empty
      )
    )
  }

  property("join absorption") {
    forAll((p: RetryPolicy[Id]) =>
      Eq[RetryPolicy[Id]].eqv(
        p.join(RetryPolicies.alwaysGiveUp[Id]),
        RetryPolicies.alwaysGiveUp[Id]
      )
    )
  }

  property("join meet distributivity") {
    forAll((p1: RetryPolicy[Id], p2: RetryPolicy[Id], p3: RetryPolicy[Id]) =>
      Eq[RetryPolicy[Id]]
        .eqv(p1.meet(p2.join(p3)), (p1.meet(p2)).join(p1.meet(p3)))
    )
  }

  property("meet join distributivity") {
    forAll((p1: RetryPolicy[Id], p2: RetryPolicy[Id], p3: RetryPolicy[Id]) =>
      Eq[RetryPolicy[Id]]
        .eqv(p1.join(p2.meet(p3)), (p1.join(p2)).meet(p1.join(p3)))
    )
  }

  property("mapK identity") {
    forAll((p: RetryPolicy[Id]) => Eq[RetryPolicy[Id]].eqv(p.mapK(FunctionK.id), p))
  }

  property("mapDelay identity") {
    forAll((p: RetryPolicy[Id]) => Eq[RetryPolicy[Id]].eqv(p.mapDelay(identity), p))
  }

  property("mapDelay composition") {
    forAll(
      (
          p: RetryPolicy[Id],
          f: FiniteDuration => FiniteDuration,
          g: FiniteDuration => FiniteDuration
      ) =>
        Eq[RetryPolicy[Id]]
          .eqv(p.mapDelay(f).mapDelay(g), p.mapDelay(f andThen g))
    )
  }

  property("flatMapDelay identity") {
    forAll((p: RetryPolicy[Id]) => Eq[RetryPolicy[Id]].eqv(p.flatMapDelay(Monad[Id].pure), p))
  }

  property("flatMapDelay composition") {
    forAll(
      (
          p: RetryPolicy[Id],
          f: FiniteDuration => FiniteDuration,
          g: FiniteDuration => FiniteDuration
      ) =>
        Eq[RetryPolicy[Id]]
          .eqv(p.flatMapDelay(f).flatMapDelay(g), p.flatMapDelay(f andThen g))
    )
  }

  property("followedBy associativity") {
    forAll((p1: RetryPolicy[Id], p2: RetryPolicy[Id], p3: RetryPolicy[Id]) =>
      Eq[RetryPolicy[Id]].eqv(
        p1.followedBy((p2).followedBy(p3)),
        (p1.followedBy(p2)).followedBy(p3)
      )
    )
  }

  property("followedBy left identity") {
    forAll((p: RetryPolicy[Id]) => Eq[RetryPolicy[Id]].eqv(RetryPolicies.alwaysGiveUp[Id].followedBy(p), p))
  }

  property("followedBy right identity") {
    forAll((p: RetryPolicy[Id]) => Eq[RetryPolicy[Id]].eqv(p.followedBy(RetryPolicies.alwaysGiveUp), p))
  }

  checkAll(
    "BoundedSemilattice[RetryPolicy]",
    BoundedSemilatticeTests[RetryPolicy[Id]].boundedSemilattice
  )
end RetryPolicyLawsSuite
