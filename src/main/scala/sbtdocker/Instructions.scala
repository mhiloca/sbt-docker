package sbtdocker

import org.apache.commons.lang3.StringEscapeUtils
import sbtdocker.staging.SourceFile

sealed trait Instruction

/**
 * A real dockerfile instruction. Contains a instruction name and arguments.
 */
trait DockerfileInstruction extends Instruction {
  def arguments: String

  def instructionName: String

  override def toString = s"$instructionName $arguments"

  @deprecated("Use toString instead.", "0.4.0")
  def toInstructionString = toString
}

/**
 * Helper for product classes.
 * Sets instruction name from the class name and uses product values as arguments.
 */
trait ProductDockerfileInstruction extends DockerfileInstruction {
  this: Product =>
  def arguments = productIterator.mkString(" ")

  def instructionName = productPrefix.toUpperCase
}

/**
 * Instruction that stages files to the staging directory.
 */
trait FileStagingInstruction extends Instruction {
  require(sources.nonEmpty, "Must have at least one source path")
  require(sources.length == 1 || destination.endsWith("/"),
    "When multiple source files are specified the destination must end with a slash \"/\".")

  def sources: Seq[SourceFile]

  def destination: String
}

/**
 * Instruction that both stages files to the staging directory and adds a Dockerfile instruction.
 *
 * A [[sbtdocker.staging.DockerfileProcessor]] is used to stage files in the staging directory,
 * which also requests the Dockerfile instruction when the file paths have been finalized.
 */
trait FileStagingDockerfileInstruction extends FileStagingInstruction {
  /**
   * Creates a Dockerfile instruction given the final paths of the sources.
   *
   * @param sources Paths to the sources in the staging directory.
   * @return Dockerfile instruction.
   */
  def dockerInstruction(sources: Seq[String]): DockerfileInstruction
}

object Instructions {

  import InstructionUtils._

  object From {
    def apply(image: ImageName): From = new From(image.toString)
  }

  /**
   * Sets the base image for the image.
   * @param image Name of the image.
   */
  case class From(image: String) extends ProductDockerfileInstruction

  object Label {

    def apply(variables: Map[String, String]): Label = {
      Label(variables.map { case (key, value) => formatKeyValue(key, value) }.mkString(" "))
    }

    def apply(key: String, value: String): Label = {
      Label(formatKeyValue(key, value))
    }

    def formatKeyValue(key: String, value: String): String = {
      key + "=" + escapeVariable(value)
    }
  }

  /**
   * Sets label(s) for the image. Docker Version >= 1.6 required.
   * Example: "A=1 B=2 C=3"
   * @param labels image labels
   */
  case class Label(labels: String) extends ProductDockerfileInstruction

  /**
   * Author of the image.
   * @param name Author name.
   */
  case class Maintainer(name: String) extends ProductDockerfileInstruction

  object Run {
    /**
     * Executes a command directly, without using a shell which means that variables will not be substituted.
     * @param commands Command list.
     */
    def exec(commands: Seq[String]) = Run(jsonArrayString(commands))

    /**
     * Executes a command through a shell (`/bin/sh`).
     * @param commands Command.
     */
    def shell(commands: Seq[String]) = Run(shellCommandString(commands))
  }

  /**
   * Executes a command when building the image.
   * @param command Command which should be executed.
   */
  case class Run(command: String) extends ProductDockerfileInstruction

  object EntryPoint {
    /**
     * Command to execute when the image starts.
     * @param commands Command list.
     */
    def exec(commands: Seq[String]) = EntryPoint(jsonArrayString(commands))

    /**
     * Command to execute through a shell (`/bin/sh`) when the image starts.
     * @param commands Command list.
     */
    def shell(commands: Seq[String]) = EntryPoint(shellCommandString(commands))
  }

  /**
   * Command to execute when the image starts.
   * @param command Command.
   */
  case class EntryPoint(command: String) extends ProductDockerfileInstruction

  object Cmd {
    /**
     * Command to execute when the image starts, or default arguments to the entry point.
     * @param commands Command list.
     */
    def exec(commands: Seq[String]) = Cmd(jsonArrayString(commands))

    /**
     * Command to execute through a shell (`/bin/sh`) when the image starts, or default arguments
     * to the entry point.
     * @param commands Command list.
     */
    def shell(commands: Seq[String]) = Cmd(shellCommandString(commands))
  }

  /**
   * When an entry point is specified `CMD` acts as default arguments that is appended the the
   * entry point command.
   * If an entry point is not specified `CMD` will be the default command for the image.
   * @param command Command.
   */
  case class Cmd(command: String) extends ProductDockerfileInstruction

  /**
   * Exposes network ports at runtime.
   * @param ports Port numbers to expose.
   */
  case class Expose(ports: Seq[Int]) extends ProductDockerfileInstruction {
    require(ports.nonEmpty, "Must expose at least one port")

    override def arguments = ports.mkString(" ")
  }

  object Env {
    def apply(variables: Map[String, String]): Env = {
      Env(variables.map { case (key, value) => formatKeyValue(key, value) }.mkString(" "))
    }

    def apply(key: String, value: String): Env = {
      Env(formatKeyValue(key, value))
    }

    def formatKeyValue(key: String, value: String): String = {
      key + "=" + escapeVariable(value)
    }
  }

  /**
   * Sets environment variables in the image.
   * Example: "A=1 B=2 C=3"
   * @param variables Environment variables.
   */
  case class Env(variables: String) extends ProductDockerfileInstruction

  object Add {
    def apply(source: SourceFile, destination: String): Add = Add(Seq(source), destination)
  }

  /**
   * Adds files to the image.
   * If a source file is a tar file it will be extracted as a directory.
   * @param sources Source files.
   * @param destination Destination path inside the container.
   */
  case class Add(sources: Seq[SourceFile], destination: String) extends FileStagingDockerfileInstruction {
    def dockerInstruction(sources: Seq[String]) = AddRaw(sources, destination)
  }

  object AddRaw {
    def apply(source: String, destination: String): AddRaw = AddRaw(Seq(source), destination)
  }

  /**
   * Adds files from the staging directory to the image.
   * If a source file is a tar file it will be extracted as a directory.
   * @param sources Source path inside the staging directory.
   * @param destination Destination path inside the container.
   */
  case class AddRaw(sources: Seq[String], destination: String) extends ProductDockerfileInstruction {
    override def instructionName = "ADD"

    override def arguments = sources.mkString(" ") + " " + destination
  }

  object Copy {
    def apply(source: SourceFile, destination: String): Copy = Copy(Seq(source), destination)
  }

  /**
   * Adds files to the image.
   * @param sources Source files. Cannot be empty.
   * @param destination Destination path inside the container.
   */
  case class Copy(sources: Seq[SourceFile], destination: String) extends FileStagingDockerfileInstruction {
    def dockerInstruction(sources: Seq[String]) = CopyRaw(sources, destination)
  }

  object CopyRaw {
    def apply(source: String, destination: String): CopyRaw = CopyRaw(Seq(source), destination)
  }

  /**
   * Adds files from the staging directory to the image.
   * @param sources Source path inside the staging directory. Cannot be empty.
   * @param destination Destination path inside the container.
   */
  case class CopyRaw(sources: Seq[String], destination: String) extends ProductDockerfileInstruction {
    override def instructionName = "COPY"

    override def arguments = sources.mkString(" ") + " " + destination
  }

  object Volume {
    def apply(path: String): Volume = Volume(Seq(path))
  }

  /**
   * Add mount points inside the container.
   * @param paths Paths inside the container. Cannot be empty.
   */
  case class Volume(paths: Seq[String]) extends ProductDockerfileInstruction {
    require(paths.nonEmpty, "Must have at least one volume path")

    override def arguments = jsonArrayString(paths)
  }

  /**
   * Sets the user inside the container that should be used for the next instructions.
   * @param username Name of the user.
   */
  case class User(username: String) extends ProductDockerfileInstruction

  /**
   * Sets the current working directory for the instructions that follow.
   * @param path Path inside the container.
   */
  case class WorkDir(path: String) extends ProductDockerfileInstruction

  /**
   * Adds a trigger of a instruction that will be run when the image is used as a base image (`FROM`).
   * @param instruction Instruction to run.
   */
  case class OnBuild(instruction: DockerfileInstruction) extends ProductDockerfileInstruction

  object StageFiles {
    def apply(source: SourceFile, destination: String): StageFiles = StageFiles(Seq(source), destination)
  }

  /**
   * Stages files. Only adds files and directories to the staging directory, will not yield an
   * instruction in the Dockerfile.
   * To use the staged files use the [[sbtdocker.Instructions.AddRaw]] and [[sbtdocker.Instructions.CopyRaw]] instructions.
   * @param sources Source files.
   * @param destination Destination path.
   */
  case class StageFiles(sources: Seq[SourceFile], destination: String) extends FileStagingInstruction

  /**
   * This class allows the user to specify a raw Dockerfile instruction.
   * Example:
   * {{{
   * Raw("RUN", "echo '123'")
   * // Will yield the following string in the Dockerfile:
   * "RUN echo '123'"
   * }}}
   * @param instructionName Name of the instruction.
   * @param arguments Argument string.
   */
  case class Raw(instructionName: String, arguments: String) extends DockerfileInstruction

}

private[sbtdocker] object InstructionUtils {
  def escapeVariable(value: String) = {
    val escapedValue = value.replace("\"", "\\\"")
    '"' + escapedValue + '"'
  }

  def shellCommandString(commands: Seq[String]) = {
    def escape(str: String) = {
      str.replace(" ", """\ """)
        .replaceAll("\\t", """\\t""")
        .replaceAll("\\n", """\\n""")
        .replace("\"", "\\\"")
    }

    commands.map(escape).mkString(" ")
  }

  def jsonArrayString(args: Seq[String]) = args.map(StringEscapeUtils.escapeJson).mkString("[\"", "\", \"", "\"]")
}
