package util

import java.io.IOException

case class FlakyHttpClient() {

  private var i = 0

  def getCatGif(): String = {
    if (i > 3) {
      "cute cat gets sleepy and falls asleep"
    } else {
      i = i + 1
      throw new IOException("Failed to download")
    }
  }

}
