package util

import cats.effect.{IO, Ref}
import java.io.IOException
import java.util.concurrent.TimeoutException

class FlakyHttpClient():
  private val counter = Ref.unsafe[IO, Int](0)

  def getCatGif: IO[String] =
    counter.getAndUpdate(_ + 1).flatMap { i =>
      if i > 3 then IO.pure("cute cat gets sleepy and falls asleep")
      else IO.raiseError(new IOException("Failed to download"))
    }

  def getRecordDetails(id: String): IO[String] =
    counter.getAndUpdate(_ + 1).flatMap { i =>
      if i > 3 then IO.pure("got some sweet details")
      else if i == 0 then IO.raiseError(new TimeoutException("Timed out getting details"))
      else IO.pure("pending")
    }
