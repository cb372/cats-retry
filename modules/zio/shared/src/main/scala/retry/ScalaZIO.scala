package retry

import zio.ZIO
import zio.clock.Clock
import zio.duration.Duration

import scala.concurrent.duration.FiniteDuration

object ScalaZIO {
  implicit val zioSleep: Sleep[ZIO[Clock, Nothing, ?]] =
    new Sleep[ZIO[Clock, Nothing, ?]] {
      override def sleep(delay: FiniteDuration): ZIO[Clock, Nothing, Unit] =
        ZIO.sleep(Duration.fromScala(delay))
    }
}
