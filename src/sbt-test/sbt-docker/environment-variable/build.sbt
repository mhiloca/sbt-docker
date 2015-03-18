enablePlugins(DockerPlugin)

name := "environment-variable"

organization := "sbtdocker"

version := "0.1.0"

val environmentValue = "b=c d&e"

dockerfile in docker := {
  new Dockerfile {
    from("busybox")
    env("a", environmentValue)
    entryPointRaw("echo $a")
  }
}

val check = taskKey[Unit]("Check")
check := {
  val process = Process("docker", Seq("run", "--rm", (imageName in docker).value.toString))
  val out = process.!!
  if (out.trim != environmentValue) sys.error("Unexpected output: " + out)
}