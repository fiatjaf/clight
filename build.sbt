enablePlugins(ScalaNativePlugin)

name                  := "poncho"
organization          := "fiatjaf"
scalaVersion          := "3.1.1"
version               := "0.1.0"
libraryDependencies   ++= Seq(
  "com.github.lolgab" %%% "native-loop-core" % "0.2.1",
  "com.softwaremill.quicklens" %%% "quicklens" % "1.8.8",
  "org.scodec" %%% "scodec-bits" % "1.1.32",
  "com.lihaoyi" %%% "upickle" % "1.6.0",
  "com.lihaoyi" %%% "ujson" % "1.6.0",
  "com.fiatjaf" %%% "sn-sha256" % "0.3.0",
  "com.fiatjaf" %%% "sn-secp256k1" % "0.2.0-SNAPSHOT",
  "com.fiatjaf" %%% "sn-chacha20poly1305" % "0.2.1",

  "com.lihaoyi" %%% "utest" % "0.7.11" % Test
)
testFrameworks  += new TestFramework("utest.runner.Framework")
nativeLinkStubs := true
