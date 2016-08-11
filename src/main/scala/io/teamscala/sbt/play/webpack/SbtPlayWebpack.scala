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
import sbt.Defaults.doClean
import sbt._
import sbt.Keys._
import spray.json.{JsonParser, DefaultJsonProtocol, JsObject, JsValue, JsBoolean}

import scala.collection.{mutable, immutable}

object SbtPlayWebpack extends AutoPlugin {

  override def requires = Play

  override def trigger = AllRequirements

  object autoImport {
    val webpack = TaskKey[Unit]("webpack", "Run the webpack module bundler.")

    object PlayWebpackKeys {
      val config   = SettingKey[File]("webpackConfig", "The location of a webpack configuration file.")
      val envVars  = SettingKey[Map[String, String]]("webpackEnvVars", "Environment variable names and values to set for webpack.")
      val contexts = TaskKey[Seq[File]]("webpackContexts", "The locations of a webpack contexts.")
    }
  }

  import autoImport._
  import autoImport.PlayWebpackKeys._

  case class NodeMissingException(cause: Throwable) extends RuntimeException("'node' is required. Please install it and add it to your PATH.", cause)
  case class NodeExecuteFailureException(exitValue: Int) extends RuntimeException("Failed to execute node.")

  override def projectSettings: Seq[Setting[_]] = Seq(
    includeFilter in webpack := AllPassFilter,
    excludeFilter in webpack := HiddenFileFilter,

    config := baseDirectory.value / "webpack.config.js",

    envVars := LocalEngine.nodePathEnv(
      (WebKeys.nodeModuleDirectories in Plugin).value.map(_.getCanonicalPath).to[immutable.Seq]
    ),
    envVars in run := envVars.value + ("NODE_ENV" -> "development"),
    envVars in Assets := envVars.value + ("NODE_ENV" -> "production"),
    envVars in TestAssets := envVars.value + ("NODE_ENV" -> "testing"),

    contexts in Assets <<= resolveContexts(Assets),
    contexts in TestAssets <<= resolveContexts(TestAssets),
    contexts <<= contexts in Assets,

    webpack in Assets <<= runWebpack(Assets) dependsOn (WebKeys.nodeModules in Plugin, WebKeys.nodeModules in Assets, WebKeys.webModules in Assets),
    webpack in TestAssets <<= runWebpack(TestAssets) dependsOn (WebKeys.nodeModules in Plugin, WebKeys.nodeModules in Assets, WebKeys.webModules in Assets),
    webpack <<= webpack in Assets,

    WebKeys.pipeline <<= WebKeys.pipeline dependsOn (webpack in Assets),
    stage in Universal <<= (stage in Universal) dependsOn (webpack in Assets),
    stage in Docker <<= (stage in Docker) dependsOn (webpack in Assets),
    dist in Universal <<= (dist in Universal) dependsOn (webpack in Assets),
    test in Test <<= (test in Test) dependsOn (webpack in TestAssets),
    test in TestAssets <<= (test in TestAssets) dependsOn (webpack in TestAssets),

    playRunHooks += WebpackWatcher(
      baseDirectory.value,
      getWebpackScript.value,
      config.value,
      (envVars in run).value,
      state.value.log,
      FileWatchService.sbt(pollInterval.value)
    ),
    playRunHooks <<= playRunHooks dependsOn (WebKeys.nodeModules in Plugin, WebKeys.nodeModules in Assets, WebKeys.webModules in Assets)
  )

  private def getWebpackScript: Def.Initialize[Task[File]] = Def.task {
    SbtWeb.copyResourceTo(
      (target in Plugin).value / webpack.key.label,
      getClass.getClassLoader.getResource("webpack.js"),
      streams.value.cacheDirectory / "copy-resource"
    )
  }

  private def resolveContexts(config: Configuration): Def.Initialize[Task[Seq[File]]] = Def.task {
    val resolveContextsScript = SbtWeb.copyResourceTo(
      (target in Plugin).value / webpack.key.label,
      getClass.getClassLoader.getResource("resolve-contexts.js"),
      streams.value.cacheDirectory / "copy-resource"
    )
    val results = runNode(
      baseDirectory.value,
      resolveContextsScript,
      args = List(PlayWebpackKeys.config.value.absolutePath),
      env = (envVars in config).value,
      log = state.value.log
    )
    import DefaultJsonProtocol._
    results.headOption.toList.flatMap(_.convertTo[Seq[String]]).map(file)
  }

  private def relativizedPath(base: File, file: File): String =
    relativeTo(base)(file).getOrElse(file.absolutePath)

  private def cached(cacheBaseDirectory: File, inStyle: FilesInfo.Style)(action: Set[File] => Unit): Set[File] => Unit = {
    import Path._
    lazy val inCache = Difference.inputs(cacheBaseDirectory / "in-cache", inStyle)
    inputs => {
      inCache(inputs) { inReport =>
        if (inReport.modified.nonEmpty) action(inReport.modified)
      }
    }
  }

  private def runWebpack(config: Configuration): Def.Initialize[Task[Unit]] = Def.task {
    val configFile = PlayWebpackKeys.config.value

    val cacheDir = streams.value.cacheDirectory / "run" / config.name
    val runUpdate = cached(cacheDir, FilesInfo.hash) { _ =>
      state.value.log.info(s"Webpack running by ${relativizedPath(baseDirectory.value, configFile)}")

      runNode(
        base = baseDirectory.value,
        script = getWebpackScript.value,
        args = List(
          configFile.absolutePath,
          JsObject("watch" -> JsBoolean(false)).toString()
        ),
        env = (envVars in config).value,
        log = state.value.log
      )

      doClean(cacheDir.getParentFile.*(DirectoryFilter).get, Seq(cacheDir))
    }

    val include = (includeFilter in webpack in config).value
    val exclude = (excludeFilter in webpack in config).value
    val inputFiles = (contexts in config).value.flatMap(_.**(include && -exclude).get).filterNot(_.isDirectory)

    runUpdate((configFile +: inputFiles).toSet)
  }

  private def runNode(base: File, script: File, args: List[String], env: Map[String, String], log: Logger): Seq[JsValue] = {
    val resultBuffer = mutable.ArrayBuffer.newBuilder[JsValue]
    val exitValue = try {
      fork(
        "node" :: script.absolutePath :: args,
        base, env,
        log.info(_),
        log.error(_), { line => resultBuffer += JsonParser(line) }
      ).exitValue()
    } catch {
      case e: IOException => throw NodeMissingException(e)
    }
    if (exitValue != 0) {
      throw NodeExecuteFailureException(exitValue)
    }
    resultBuffer.result()
  }

  private def forkNode(base: File, script: File, args: List[String], env: Map[String, String], log: Logger): Process = try {
    fork("node" :: script.absolutePath :: args, base, env, log.info(_), log.error(_), _ => ())
  } catch {
    case e: IOException => throw NodeMissingException(e)
  }

  private val ResultEscapeChar: Char = 0x10

  private def fork(
    command: List[String], base: File, env: Map[String, String],
    processOutput: (String => Unit),
    processError: (String => Unit),
    processResult: (String => Unit)
  ): Process = {
    val io = new ProcessIO(
      writeInput = BasicIO.input(false),
      processOutput = BasicIO.processFully { line =>
        if (line.indexOf(ResultEscapeChar) == -1) {
          processOutput(line)
        } else {
          val (out, result) = line.span(_ != ResultEscapeChar)
          if (!out.isEmpty) {
            processOutput(out)
          }
          processResult(result.drop(1))
        }
      },
      processError = BasicIO.processFully(processError),
      inheritInput = BasicIO.inheritInput(false)
    )
    if (IS_OS_WINDOWS)
      Process("cmd" :: "/c" :: command, base, env.toSeq: _*).run(io)
    else
      Process(command, base, env.toSeq: _*).run(io)
  }

  object WebpackWatcher {
    def apply(base: File, script: File, config: File, env: Map[String, String], log: Logger, fileWatchService: FileWatchService): PlayRunHook = {

      object WebpackSubProcessWatcher {
        private[this] var process: Option[Process] = None

        def start(): Unit = {
          stop()
          process = Some(forkNode(
            base = base,
            script = script,
            args = List(
              config.absolutePath,
              JsObject("watch" -> JsBoolean(true)).toString()
            ),
            env = env,
            log = log
          ))
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
