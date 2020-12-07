package util

import java.io.IOException
import java.util.concurrent.TimeoutException

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

  def getRecordDetails(id: String): String = {
    if (i > 3) {
      "got some sweet details"
    } else if (i == 0) {
      i = i + 1
      throw new TimeoutException("Timed out getting details")
    } else {
      i = i + 1
      "pending"
    }
  }
}
