package util

case class LoadedDie(rolls: Int*) {
  private var i = -1

  def roll(): Int = {
    i = i + 1
    if (i >= rolls.length) {
      i = 0
    }
    rolls(i)
  }
}
