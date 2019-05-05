package org.azuni.twineserver

import org.azuni.twineserver.app.App
import com.twitter.finagle.Http
import com.twitter.util.Await

object Main {
  def main(_args: Array[String]): Unit = {
    var app    = App.init()
    val server = Http.server.serve(":8081", app.toService)
    Await.ready(server)
  }
}
