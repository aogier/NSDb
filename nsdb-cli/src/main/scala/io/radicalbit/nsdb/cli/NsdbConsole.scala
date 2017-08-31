package io.radicalbit.nsdb.cli

import scala.tools.nsc.Settings

object NsdbConsole extends App {
  val settings = new Settings
  settings.usejavacp.value = true
  settings.deprecation.value = true

  new NsdbILoop().process(settings)
}