package util

import cats.effect.IO

class LoadedDie(rolls: Int*):
  private var i = -1

  def roll: IO[Int] = IO {
    i = i + 1
    if i >= rolls.length then i = 0
    rolls(i)
  }
