package io.teamscala.sbt.play.webpack

import java.io.IOException
import java.net.InetSocketAddress

import com.typesafe.sbt.jse._
import com.typesafe.sbt.jse.SbtJsEngine.autoImport.JsEngineKeys._
import com.typesafe.sbt.jse.JsTaskImport.JsTaskKeys._
import com.typesafe.sbt.jse.SbtJsTask.JsTaskFailure
import com.typesafe.sbt.web._
import com.typesafe.sbt.web.SbtWeb.autoImport._
import com.typesafe.sbt.web.SbtWeb.autoImport.WebKeys._
import org.apache.commons.lang3.SystemUtils.IS_OS_WINDOWS
import play.sbt.PlayImport.PlayKeys.playRunHooks
import play.sbt.{Play, PlayRunHook}
import sbt._
import sbt.Keys._
import spray.json.{JsString, JsBoolean, JsObject}

import scala.util.control.NoStackTrace

object SbtPlayWebpack extends AutoPlugin {

  override def requires = Play

  override def trigger = AllRequirements

  object autoImport {
    val webpack = TaskKey[Seq[File]]("webpack", "Run the webpack module bundler.")

    object PlayWebpackKeys {
      val config = SettingKey[File]("webpackConfig", "The location of a webpack configuration file.")
      val script = TaskKey[File]("webpackScript", "The location of a webpack script file.")
    }
  }

  import autoImport._
  import autoImport.PlayWebpackKeys._

  case object NodeMissingException extends RuntimeException("'node' is required. Please install it and add it to your PATH.")
  case object WebpackMissingException extends RuntimeException("Cannot find module 'webpack'. Please install 'webpack' with npm: npm install webpack")
  case object WebpackFailure extends NoStackTrace

  override def projectSettings: Seq[Setting[_]] = Seq(
    includeFilter in webpack := AllPassFilter,
    excludeFilter in webpack := HiddenFileFilter,
    sourceDirectory in webpack := (sourceDirectory in Assets).value,
    resourceManaged in webpack := webTarget.value / webpack.key.label,
    resourceGenerators in Assets <+= webpack,
    managedResourceDirectories in Assets += (resourceManaged in webpack).value,
    config := baseDirectory.value / "webpack.config.js",
    script <<= getWebpackScript dependsOn (nodeModules in Plugin, nodeModules in Assets, webModules in Assets),
    webpack <<= runWebpack dependsOn (nodeModules in Plugin, nodeModules in Assets, webModules in Assets),
    playRunHooks <+= (baseDirectory, script, config, sourceDirectory in webpack, resourceManaged in webpack, streams).map {
      (base, script, config, context, outputPath, streams) => WebpackWatch(base, script, config, context, outputPath, streams.log)
    }
  )

  private def getWebpackScript: Def.Initialize[Task[File]] = Def.task {
    ((nodeModuleDirectories in Assets).value ++ (nodeModuleDirectories in Plugin).value)
      .map(_ / "webpack" / "bin" / "webpack.js").find(_.exists)
      .getOrElse(throw WebpackMissingException)
  }

  private def relativizedPath(base: File, file: File): String =
    relativeTo(base)(file).getOrElse(file.absolutePath)

  private def runWebpack: Def.Initialize[Task[Seq[File]]] = Def.task {
    if (WebpackWatch.isRunning) {
      (resourceManaged in webpack).value.***.get
    } else {
      val context = (sourceDirectory in webpack).value
      val outputPath = (resourceManaged in webpack).value

      val runUpdate = FileFunction.cached(streams.value.cacheDirectory / webpack.key.label, FilesInfo.hash) { _ =>
        streams.value.log.info(s"Webpack running by ${relativizedPath(baseDirectory.value, config.value)}")

        val nodeModulePaths = (nodeModuleDirectories in Plugin).value.map(_.getCanonicalPath)

        val webpackExecutable = SbtWeb.copyResourceTo(
          (target in Plugin).value / webpack.key.label,
          getClass.getClassLoader.getResource("webpack.js"),
          streams.value.cacheDirectory / "copy-resource"
        )

        val args = Seq(config.value.absolutePath, JsObject(
          "context" -> JsString(context.absolutePath),
          "output" -> JsObject(
            "path" -> JsString(outputPath.absolutePath)
          )
        ).toString())

        val execution = try SbtJsTask.executeJs(
          state.value,
          (engineType in webpack).value,
          (command in webpack).value,
          nodeModulePaths,
          webpackExecutable,
          args,
          (timeoutPerSource in webpack).value
        ) catch {
          case _: JsTaskFailure => throw WebpackFailure
        }
        execution match {
          case Seq(JsBoolean(true))  => outputPath.***.get.toSet
          case Seq(JsBoolean(false)) => throw WebpackFailure
        }
      }

      val include = (includeFilter in webpack).value
      val exclude = (excludeFilter in webpack).value
      val inputFiles = (context ** (include && -exclude)).get.filterNot(_.isDirectory)
      runUpdate((config.value +: inputFiles).toSet).filter(_.isFile).toSeq
    }
  }

  private def execNode(base: File, script: String, args: List[String], log: Logger): Process = {
    def nodeInstalled: Boolean = try {
      runProcess(List("node", "-v"), base, None)
      true
    } catch {
      case _: IOException => false
    }
    if (nodeInstalled)
      runProcess("node" :: script :: args, base, Some(log))
    else
      throw NodeMissingException
  }

  private def runProcess(command: List[String], base: File, log: Option[Logger]): Process = {
    val processIO = log.map { log =>
      new ProcessIO(
        writeInput = BasicIO.close,
        processOutput = BasicIO.processFully(s => log.info(s)),
        processError = BasicIO.processFully(s => log.error(s)),
        inheritInput = _ => false
      )
    }.getOrElse {
      new ProcessIO(BasicIO.close, BasicIO.close, BasicIO.close, _ => false)
    }
    if (IS_OS_WINDOWS)
      Process("cmd" :: "/c" :: command, base).run(processIO)
    else
      Process(command, base).run(processIO)
  }

  object WebpackWatch {
    @volatile private[this] var running: Boolean = false

    def isRunning: Boolean = running

    def apply(base: File, script: File, config: File, context: File, outputPath: File, log: Logger): PlayRunHook = {

      object WebpackSubProcessHook extends PlayRunHook {
        private[this] var process: Option[Process] = None

        override def afterStarted(addr: InetSocketAddress): Unit = {
          if (running) {
            sys.error("WebpackWatch is already running, must be only run once.")
          }
          log.info(s"Webpack running in watch mode by ${relativizedPath(base, config)}")

          running = true
          process = Option(
            execNode(base, script.absolutePath, List(
              "--config", config.absolutePath,
              "--context", context.absolutePath,
              "--output-path", outputPath.absolutePath,
              "--colors",
              "--watch"
            ), log)
          )
        }

        override def afterStopped(): Unit = {
          process.foreach(_.destroy())
          process = None
          running = false

          log.info(s"Webpack stopped in watch mode by ${relativizedPath(base, config)}")
        }
      }

      WebpackSubProcessHook
    }
  }
}
