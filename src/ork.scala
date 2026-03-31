package ork

import zio.*
import ork.app.Ork

object Main extends ZIOAppDefault:
  override def run =
    for
      args   <- getArgs
      result <- Ork.run(args)
    yield result
