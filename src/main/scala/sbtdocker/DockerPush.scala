package sbtdocker

import sbt._

import scala.sys.process.{Process, ProcessLogger}

object DockerPush {
  /**
   * Push an image to a registry or docker hub
   *
   * @param dockerPath path to the docker binary
   * @param imageNames names of the images to push
   * @param log logger
   */
  def apply(dockerPath: String, imageNames: Seq[ImageName], log: Logger) {
    imageNames.foreach {
      imageName =>
        log.info(s"Pushing docker image with name: '$imageName'")

        val processLog = ProcessLogger({ line =>
          log.info(line)
        }, { line =>
          log.info(line)
        })

        val command = dockerPath :: "push" :: imageName.toString :: Nil
        log.debug(s"Running command: '${command.mkString(" ")}'")

        val processOutput = Process(command).lines(processLog)
        processOutput.foreach { line =>
          log.info(line)
        }
    }
  }
}
