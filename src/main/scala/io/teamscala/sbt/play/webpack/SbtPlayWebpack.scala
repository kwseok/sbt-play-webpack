package io.teamscala.sbt.play.webpack

import java.io.IOException
import java.net.InetSocketAddress

import com.typesafe.jse.LocalEngine
import com.typesafe.sbt.packager.universal.UniversalPlugin.autoImport.{Universal, stage, dist}
import com.typesafe.sbt.packager.docker.DockerPlugin.autoImport.Docker
import com.typesafe.sbt.web.SbtWeb
import com.typesafe.sbt.web.SbtWeb.autoImport.{WebKeys, _}
import org.apache.commons.lang3.SystemUtils.IS_OS_WINDOWS
import play.runsupport.{FileWatchService, FileWatcher}
import play.sbt.PlayImport.PlayKeys.playRunHooks
import play.sbt.{Play, PlayRunHook}
import sbt._
import sbt.Keys._
import spray.json.{JsString, JsBoolean, JsObject}

import scala.collection.immutable

object SbtPlayWebpack extends AutoPlugin {

  override def requires = Play

  override def trigger = AllRequirements

  object autoImport {
    val webpack = TaskKey[Seq[File]]("webpack", "Run the webpack module bundler.")

    object PlayWebpackKeys {
      val script         = TaskKey[File]("webpackScript", "The location of a webpack script file.")
      val config         = SettingKey[File]("webpackConfig", "The location of a webpack configuration file.")
      val envVars        = SettingKey[Map[String, String]]("webpackEnvVars", "Environment variable names and values to set for webpack.")
      val displayOptions = SettingKey[JsObject]("webpackDisplayOptions", "Display options for statistics.")
    }
  }

  import autoImport._
  import autoImport.PlayWebpackKeys._

  case object NodeMissingException extends RuntimeException("'node' is required. Please install it and add it to your PATH.")
  case object WebpackFailureException extends RuntimeException("Webpack failed to running.")

  override def projectSettings: Seq[Setting[_]] = Seq(
    includeFilter in webpack := AllPassFilter,
    excludeFilter in webpack := HiddenFileFilter,
    sourceDirectory in webpack := (sourceDirectory in Assets).value,
    resourceManaged in webpack := WebKeys.webTarget.value / webpack.key.label,
    resourceGenerators in Assets <+= Def.task((resourceManaged in webpack).value.***.get.filter(_.isFile)),
    managedResourceDirectories in Assets += (resourceManaged in webpack).value,

    script <<= getWebpackScript,
    config := baseDirectory.value / "webpack.config.js",
    displayOptions := JsObject("colors" -> JsBoolean(true)),

    envVars := LocalEngine.nodePathEnv((WebKeys.nodeModuleDirectories in Plugin).value.map(_.getCanonicalPath).to[immutable.Seq]),
    envVars in run := envVars.value + ("NODE_ENV" -> "development"),
    envVars in webpack := envVars.value + ("NODE_ENV" -> "production"),

    webpack <<= runWebpackTask dependsOn (WebKeys.nodeModules in Plugin, WebKeys.nodeModules in Assets, WebKeys.webModules in Assets),

    WebKeys.pipeline <<= WebKeys.pipeline dependsOn webpack,
    stage in Universal <<= (stage in Universal) dependsOn webpack,
    stage in Docker <<= (stage in Docker) dependsOn webpack,
    dist in Universal <<= (dist in Universal) dependsOn webpack,
    test in Test <<= (test in Test) dependsOn webpack,
    test in TestAssets <<= (test in TestAssets) dependsOn webpack,

    playRunHooks += WebpackWatcher(
      baseDirectory.value,
      script.value,
      config.value,
      (sourceDirectory in webpack).value,
      (resourceManaged in webpack).value,
      displayOptions.value,
      (envVars in run).value,
      state.value.log,
      FileWatchService.sbt(pollInterval.value)
    )
  )

  private def getWebpackScript: Def.Initialize[Task[File]] = Def.task {
    SbtWeb.copyResourceTo(
      (target in Plugin).value / webpack.key.label,
      getClass.getClassLoader.getResource("webpack.js"),
      streams.value.cacheDirectory / "copy-resource"
    )
  }

  private def relativizedPath(base: File, file: File): String =
    relativeTo(base)(file).getOrElse(file.absolutePath)

  private def runWebpackTask: Def.Initialize[Task[Seq[File]]] = Def.task {
    val include = (includeFilter in webpack).value
    val exclude = (excludeFilter in webpack).value
    val context = (sourceDirectory in webpack).value
    val inputFiles = (context ** (include && -exclude)).get.filterNot(_.isDirectory)
    val outputDir = (resourceManaged in webpack).value

    val runUpdate = FileFunction.cached(streams.value.cacheDirectory / webpack.key.label, FilesInfo.hash) { _ =>
      state.value.log.info(s"Webpack running by ${relativizedPath(baseDirectory.value, config.value)}")

      IO.delete(outputDir)

      val execution = runWebpack(
        baseDirectory.value,
        script.value,
        config.value,
        context,
        outputDir,
        watch = false,
        displayOptions.value,
        (envVars in webpack).value,
        state.value.log
      )

      if (execution.exitValue() == 0)
        outputDir.***.get.filter(_.isFile).toSet
      else
        throw WebpackFailureException
    }

    runUpdate((config.value +: inputFiles).toSet).toSeq
  }

  private def runWebpack(
    base: File, script: File,
    config: File, context: File, outputDir: File,
    watch: Boolean, displayOptions: JsObject,
    env: Map[String, String], log: Logger
  ): Process = {
    val options = JsObject(
      "context" -> JsString(context.absolutePath),
      "output" -> JsObject(
        "path" -> JsString(outputDir.absolutePath)
      ),
      "watch" -> JsBoolean(watch)
    ).toString()
    runNode(
      base, script.absolutePath,
      List(config.absolutePath, options, displayOptions.toString),
      env, log
    )
  }

  private def runNode(base: File, script: String, args: List[String], env: Map[String, String], log: Logger): Process = {
    def nodeInstalled: Boolean = try {
      runProcess(List("node", "-v"), base, env, None)
      true
    } catch {
      case _: IOException => false
    }
    if (nodeInstalled)
      runProcess("node" :: script :: args, base, env, Some(log))
    else
      throw NodeMissingException
  }

  private def runProcess(command: List[String], base: File, env: Map[String, String], log: Option[Logger]): Process = {
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
      Process("cmd" :: "/c" :: command, base, env.toSeq: _*).run(processIO)
    else
      Process(command, base, env.toSeq: _*).run(processIO)
  }

  object WebpackWatcher {
    def apply(
      base: File, script: File,
      config: File, context: File, outputDir: File,
      displayOptions: JsObject, env: Map[String, String], log: Logger,
      fileWatchService: FileWatchService
    ): PlayRunHook = {

      object WebpackSubProcessWatcher {
        private[this] var process: Option[Process] = None

        def start(): Unit = {
          stop()
          IO.delete(outputDir)
          process = Some(runWebpack(base, script, config, context, outputDir, watch = true, displayOptions, env, log))
        }

        def stop(): Unit = for (p <- process) {
          p.destroy()
          process = None
        }
      }

      object WebpackSubProcessHook extends PlayRunHook {
        private[this]           var configWatcher : Option[FileWatcher] = None
        @volatile private[this] var configWatching: Boolean             = false

        override def afterStarted(addr: InetSocketAddress): Unit = {
          log.info(s"Webpack watcher starting by ${relativizedPath(base, config)}")
          WebpackSubProcessWatcher.start()
          configWatcher = Some(
            fileWatchService.watch(Seq(config), () => {
              if (configWatching) {
                log.info(s"Webpack configuration changes, webpack watcher restarting by ${relativizedPath(base, config)}")
                WebpackSubProcessWatcher.stop()
                WebpackSubProcessWatcher.start()
              } else {
                configWatching = true
              }
            })
          )
        }

        override def afterStopped(): Unit = {
          configWatcher.foreach(_.stop())
          configWatcher = None
          configWatching = false
          WebpackSubProcessWatcher.stop()
          log.info(s"Webpack watcher stopped by ${relativizedPath(base, config)}")
        }
      }

      WebpackSubProcessHook
    }
  }
}
